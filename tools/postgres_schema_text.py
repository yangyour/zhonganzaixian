"""Lossless handling of trusted pg_dump schema text, without connection or private-file access.

Only known top-level pg_dump client guards and the redundant empty public-schema
creation may be removed. SQL string, identifier and function-body spans are opaque;
Unicode line separators and carriage returns inside them must remain byte-identical.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import re


class SchemaTextError(ValueError):
    """A bounded reason code only; never include source SQL in a diagnostic."""


@dataclass(frozen=True)
class Statement:
    start: int
    end: int
    words: tuple[str, ...]


@dataclass(frozen=True)
class Scan:
    statements: tuple[Statement, ...]
    literals: tuple[tuple[int, int, str], ...]
    guards: tuple[tuple[int, int], ...]


_DOLLAR = re.compile(r'\$(?:[^\W\d]\w*)?\$', re.UNICODE)
_CLIENT_GUARD = re.compile(r'\\(restrict|unrestrict)[ \t]+([!-\[\]-~]{1,256})[ \t]*\r?\Z')
_SPACE = ' \t\r\n\f\v'
_MAX_BYTES = 64 * 1024 * 1024


def _identifier(char: str) -> bool:
    return char.isalnum() or char in '_$'


def scan_schema(text: str) -> Scan:
    """Scan delimiters without normalizing text or interpreting function bodies.

    This is intentionally a scanner, not a SQL rewriter or a general SQL firewall.
    Its caller still verifies the pinned project, database ownership and schema.
    """
    if not isinstance(text, str) or '\0' in text:
        raise SchemaTextError('INVALID_SCHEMA_TEXT')
    try:
        if len(text.encode('utf-8')) > _MAX_BYTES:
            raise SchemaTextError('SCHEMA_TEXT_TOO_LARGE')
    except UnicodeEncodeError:
        raise SchemaTextError('INVALID_SCHEMA_ENCODING') from None
    statements: list[Statement] = []
    literals: list[tuple[int, int, str]] = []
    guards: list[tuple[int, int]] = []
    words: list[str] = []
    start: int | None = None
    guard_key = None
    guard_closed = False
    index = 0
    physical_start = True
    while index < len(text):
        char = text[index]
        if physical_start and char in ' \t':
            index += 1
            continue
        if physical_start and char == '\\':
            end = text.find('\n', index)
            end = len(text) if end < 0 else end
            match = _CLIENT_GUARD.fullmatch(text[index:end])
            if match is None or start is not None:
                raise SchemaTextError('UNEXPECTED_DUMP_METACOMMAND')
            kind, key = match.groups()
            if kind == 'restrict':
                if guard_key is not None or guard_closed:
                    raise SchemaTextError('INVALID_DUMP_GUARD_PAIR')
                guard_key = key
            else:
                if guard_key != key or guard_closed:
                    raise SchemaTextError('INVALID_DUMP_GUARD_PAIR')
                guard_closed = True
            end += end < len(text)
            guards.append((index, end))
            index = end
            physical_start = True
            continue
        if char in _SPACE:
            if char == '\n':
                physical_start = True
            index += 1
            continue
        physical_start = False
        if text.startswith('--', index):
            # PostgreSQL comments end at CR or LF; neither is rewritten.
            end = index + 2
            while end < len(text) and text[end] not in '\r\n':
                end += 1
            index = end
            continue
        if text.startswith('/*', index):
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith('/*', index):
                    depth += 1
                    index += 2
                elif text.startswith('*/', index):
                    depth -= 1
                    index += 2
                else:
                    if text[index] == '\n':
                        physical_start = True
                    elif text[index] not in ' \t\r':
                        physical_start = False
                    index += 1
            if depth:
                raise SchemaTextError('TRUNCATED_SCHEMA_COMMENT')
            continue
        if start is None and char != ';':
            start = index
        if char in '\'"':
            quote = char
            begin = index
            escaped = quote == "'" and index > 0 and text[index - 1] in 'Ee' \
                and (index < 2 or not _identifier(text[index - 2]))
            index += 1
            while index < len(text):
                if escaped and text[index] == '\\':
                    index += 2
                elif text[index] == quote:
                    if index + 1 < len(text) and text[index + 1] == quote:
                        index += 2
                    else:
                        index += 1
                        break
                else:
                    index += 1
            else:
                raise SchemaTextError('TRUNCATED_SCHEMA_QUOTE')
            if index > len(text):
                raise SchemaTextError('TRUNCATED_SCHEMA_ESCAPE')
            literals.append((begin, index, 'ESCAPE' if escaped else 'STRING' if quote == "'" else 'IDENTIFIER'))
            continue
        if char == '$' and (index == 0 or not _identifier(text[index - 1])):
            match = _DOLLAR.match(text, index)
            if match:
                delimiter = match.group()
                end = text.find(delimiter, match.end())
                if end < 0:
                    raise SchemaTextError('TRUNCATED_SCHEMA_DOLLAR_QUOTE')
                end += len(delimiter)
                literals.append((index, end, 'DOLLAR'))
                index = end
                continue
        if char.isalpha() or char == '_':
            end = index + 1
            while end < len(text) and _identifier(text[end]):
                end += 1
            if len(words) < 4:
                words.append(text[index:end].upper())
            index = end
            continue
        if char == ';':
            if start is not None:
                command = words[1:]
                if command[:2] == ['OR', 'REPLACE']:
                    command = command[2:]
                if words and words[0] in ('CREATE', 'ALTER', 'DROP') and command \
                        and command[0] in ('DATABASE', 'ROLE', 'USER', 'SYSTEM'):
                    raise SchemaTextError('CLUSTER_OR_DATABASE_COMMAND_IN_SCHEMA_DUMP')
                statements.append(Statement(start, index + 1, tuple(words)))
            start, words = None, []
        index += 1
    if start is not None:
        raise SchemaTextError('TRUNCATED_SCHEMA_STATEMENT')
    if guard_key is not None and not guard_closed:
        raise SchemaTextError('INVALID_DUMP_GUARD_PAIR')
    return Scan(tuple(statements), tuple(literals), tuple(guards))


def _remove(text: str, spans: list[tuple[int, int]]) -> str:
    for start, end in sorted(spans, reverse=True):
        text = text[:start] + text[end:]
    return text


def literal_bytes_sha256(text: str) -> str:
    """Length-prefixed literal tokens, including exact original line separators."""
    result = hashlib.sha256()
    for start, end, kind in scan_schema(text).literals:
        value = (kind + '\0' + text[start:end]).encode('utf-8')
        result.update(len(value).to_bytes(8, 'big'))
        result.update(value)
    return result.hexdigest()


def filter_schema_dump(text: str) -> str:
    scanned = scan_schema(text)
    result = _remove(text, list(scanned.guards))
    if literal_bytes_sha256(result) != literal_bytes_sha256(text):
        raise SchemaTextError('SCHEMA_LITERAL_BYTES_CHANGED')
    return result


def reuse_empty_public_schema(text: str) -> str:
    """Remove only the top-level redundant declaration, never a line in a body."""
    scanned = scan_schema(text)
    declarations = [(item.start, item.end) for item in scanned.statements
                    if re.fullmatch(r'CREATE[ \t\r\n]+SCHEMA[ \t\r\n]+public[ \t\r\n]*;',
                                    text[item.start:item.end], flags=re.IGNORECASE)]
    if len(declarations) > 1:
        raise SchemaTextError('DUPLICATE_PUBLIC_SCHEMA_CREATION')
    result = _remove(text, declarations)
    if literal_bytes_sha256(result) != literal_bytes_sha256(text):
        raise SchemaTextError('SCHEMA_LITERAL_BYTES_CHANGED')
    return result


def sql_literal_values(expression: str) -> tuple[str, ...]:
    """Values in PostgreSQL's deparsed expressions; structural casts may differ.

    Function source is compared separately as exact bytes. This function is for
    pg_get_expr/pg_get_constraintdef output, not user SQL or custom UESCAPE syntax.
    """
    values = []
    for start, end, kind in scan_schema('SELECT ' + expression + ';').literals:
        token = ('SELECT ' + expression + ';')[start:end]
        if kind == 'IDENTIFIER':
            continue
        if kind == 'DOLLAR':
            delimiter = _DOLLAR.match(token).group()
            values.append(token[len(delimiter):-len(delimiter)])
            continue
        body = token[1:-1]
        if kind != 'ESCAPE':
            values.append(body.replace("''", "'"))
            continue
        decoded = bytearray()
        index = 0
        while index < len(body):
            char = body[index]
            index += 1
            if char == "'":
                if index >= len(body) or body[index] != "'":
                    raise SchemaTextError('INVALID_CATALOGUE_QUOTE')
                decoded.extend(b"'")
                index += 1
                continue
            if char != '\\':
                decoded.extend(char.encode('utf-8'))
                continue
            if index == len(body):
                raise SchemaTextError('INVALID_CATALOGUE_ESCAPE')
            char = body[index]
            index += 1
            if char in '01234567':
                number = char
                while index < len(body) and len(number) < 3 and body[index] in '01234567':
                    number += body[index]
                    index += 1
                decoded.append(int(number, 8) % 256)
            elif char in 'xuU':
                count = {'x': 2, 'u': 4, 'U': 8}[char]
                number = ''
                while index < len(body) and len(number) < count and body[index] in '0123456789abcdefABCDEF':
                    number += body[index]
                    index += 1
                if not number or char != 'x' and len(number) != count:
                    raise SchemaTextError('INVALID_CATALOGUE_ESCAPE')
                try:
                    if char == 'x':
                        decoded.extend(bytes([int(number,16)]))
                    else:
                        decoded.extend(chr(int(number,16)).encode('utf-8'))
                except ValueError:
                    raise SchemaTextError('INVALID_CATALOGUE_ESCAPE') from None
            else:
                decoded.extend({'b': '\b', 'f': '\f', 'n': '\n', 'r': '\r', 't': '\t'}.get(char, char).encode('utf-8'))
        try:
            values.append(decoded.decode('utf-8'))
        except UnicodeDecodeError:
            raise SchemaTextError('INVALID_CATALOGUE_ENCODING') from None
    return tuple(values)


def catalog_literal_fingerprints(connection) -> dict[str, dict]:
    """Read-only literal/function preservation proof, not full runtime-schema approval."""
    objects = {}
    with connection.cursor() as cursor:
        cursor.execute("SELECT 'constraint:'||t.relname||':'||c.conname,pg_get_constraintdef(c.oid) "
                       "FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                       "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' "
                       "UNION ALL SELECT 'default:'||t.relname||':'||a.attname,pg_get_expr(d.adbin,d.adrelid) "
                       "FROM pg_attrdef d JOIN pg_class t ON t.oid=d.adrelid "
                       "JOIN pg_namespace n ON n.oid=t.relnamespace "
                       "JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.adnum WHERE n.nspname='public' "
                       "UNION ALL SELECT 'index:'||i.relname,pg_get_indexdef(i.oid) "
                       "FROM pg_class i JOIN pg_namespace n ON n.oid=i.relnamespace "
                       "WHERE n.nspname='public' AND i.relkind='i' ORDER BY 1")
        for identity, expression in cursor.fetchall():
            if identity in objects:
                raise SchemaTextError('DUPLICATE_CATALOGUE_IDENTITY')
            values = sql_literal_values(expression)
            encoded = json.dumps(values, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
            objects[identity] = {'kind': 'DEPARSED_LITERAL_VALUES', 'count': len(values),
                                 'sha256': hashlib.sha256(encoded).hexdigest()}
        cursor.execute("SELECT p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',p.prosrc "
                       "FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' ORDER BY 1")
        for identity, body in cursor.fetchall():
            identity = 'function:' + identity
            if identity in objects:
                raise SchemaTextError('DUPLICATE_CATALOGUE_IDENTITY')
            objects[identity] = {'kind': 'EXACT_FUNCTION_SOURCE',
                                 'sha256': hashlib.sha256(body.encode('utf-8')).hexdigest()}
    return objects
