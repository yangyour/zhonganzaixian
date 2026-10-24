"""Pure regression tests for lossless schema preparation; no database or private input."""
import importlib.util
from pathlib import Path
import sys
import unittest

from postgres_schema_text import (
    SchemaTextError, filter_schema_dump, literal_bytes_sha256, reuse_empty_public_schema,
    sql_literal_values,
)


class SchemaTextTests(unittest.TestCase):
    def test_every_unicode_line_separator_inside_literal_survives_both_steps(self):
        literal = '\t\n\v\f\r\x1c\x1d\x1e\x85\u2028\u2029'
        sql = "CREATE SCHEMA public;\nCREATE TABLE public.t(v text CHECK(v !~ '[" + literal + "]'));\n"
        prepared = reuse_empty_public_schema(filter_schema_dump(sql))
        self.assertIn("'[" + literal + "]'", prepared)
        self.assertEqual(literal_bytes_sha256(sql), literal_bytes_sha256(prepared))
        self.assertNotEqual(literal_bytes_sha256(sql), literal_bytes_sha256('\n'.join(sql.splitlines())))

    def test_only_known_client_guard_lines_are_removed(self):
        sql = "\\restrict Ab123_-\nCREATE SCHEMA public;\r\nSELECT 'x\ry\u2028z';\n\\unrestrict Ab123_-\n"
        self.assertEqual("CREATE SCHEMA public;\r\nSELECT 'x\ry\u2028z';\n", filter_schema_dump(sql))

    def test_function_bodies_are_opaque_even_when_their_lines_look_like_commands(self):
        body = "\nCREATE SCHEMA public;\n\\restrict not_a_client_command\nCREATE ROLE literal;\n"
        sql = 'CREATE SCHEMA public;\nCREATE FUNCTION public.f() RETURNS text AS $body$' + body + '$body$ LANGUAGE sql;\n'
        prepared = reuse_empty_public_schema(filter_schema_dump(sql))
        self.assertEqual('\nCREATE FUNCTION public.f() RETURNS text AS $body$' + body + '$body$ LANGUAGE sql;\n', prepared)

    def test_single_and_quoted_identifier_content_is_not_filtered(self):
        sql = "SELECT 'first;\nCREATE SCHEMA public;\nDROP DATABASE literal;', \"DROP ROLE literal\";"
        self.assertEqual(sql, reuse_empty_public_schema(filter_schema_dump(sql)))

    def test_escape_strings_and_doubled_quotes_do_not_end_a_literal_early(self):
        sql = r"SELECT E'first\'quoted; DROP ROLE literal;\nlast', 'one''two; CREATE DATABASE literal;';"
        self.assertEqual(sql, filter_schema_dump(sql))

    def test_unicode_and_empty_dollar_delimiters_remain_opaque(self):
        for tag in ('$$', '$f_1$', '$函数$'):
            sql = 'SELECT ' + tag + 'CREATE DATABASE literal;\r\u2028' + tag + ';'
            self.assertEqual(sql, filter_schema_dump(sql))

    def test_nested_comments_are_not_cluster_commands(self):
        sql = '/* CREATE ROLE a; /* DROP DATABASE b; */ */\nSELECT 1; -- ALTER SYSTEM SET x=1;\rSELECT 2;'
        self.assertEqual(sql, filter_schema_dump(sql))

    def test_top_level_cluster_commands_are_rejected_across_comments_and_newlines(self):
        for sql in ('CREATE DATABASE forbidden;', 'ALTER\nROLE forbidden NOLOGIN;',
                    'DROP /* comment */ USER forbidden;', 'ALTER SYSTEM SET work_mem=1;',
                    'SELECT 1;\nCREATE /* nested /* comment */ */\nDATABASE forbidden;',
                    '-- ignored\rDROP ROLE forbidden;'):
            with self.subTest(sql=sql), self.assertRaisesRegex(SchemaTextError, '^CLUSTER_OR_DATABASE_COMMAND'):
                filter_schema_dump(sql)

    def test_a_standard_string_backslash_does_not_escape_its_closing_quote(self):
        with self.assertRaisesRegex(SchemaTextError, '^CLUSTER_OR_DATABASE_COMMAND'):
            filter_schema_dump(r"SELECT '\\'; DROP ROLE forbidden;")

    def test_unexpected_client_commands_and_unpaired_guards_fail_closed(self):
        for sql in ('\\connect other\n', '\\restrict a\nSELECT 1;', '\\unrestrict a\n',
                    '\\restrict a\nSELECT 1;\n\\unrestrict b\n',
                    '\\restrict a\n\\restrict a\nSELECT 1;\n\\unrestrict a\n',
                    'SELECT\n\\restrict a\n1;\n\\unrestrict a\n'):
            with self.subTest(sql=sql), self.assertRaises(SchemaTextError):
                filter_schema_dump(sql)

    def test_truncated_text_is_never_presented_as_a_restorable_schema(self):
        for sql in ("SELECT 'unfinished", 'SELECT "unfinished', 'SELECT $$unfinished',
                    '/* unfinished', 'SELECT 1', "SELECT E'backslash\\"):
            with self.subTest(sql=sql), self.assertRaises(SchemaTextError):
                filter_schema_dump(sql)

    def test_public_schema_removal_is_top_level_and_does_not_eat_the_following_statement(self):
        sql = "/* header */ CREATE SCHEMA public; SELECT 'untouched';\n"
        self.assertEqual("/* header */  SELECT 'untouched';\n", reuse_empty_public_schema(sql))

    def test_duplicate_schema_declarations_are_rejected(self):
        with self.assertRaisesRegex(SchemaTextError, '^DUPLICATE_PUBLIC_SCHEMA_CREATION$'):
            reuse_empty_public_schema('CREATE SCHEMA public;\nCREATE SCHEMA public;')

    def test_invalid_text_does_not_leak_its_contents_in_errors(self):
        for text in ("SELECT 'private\0value';", "SELECT '\ud800';"):
            with self.assertRaises(SchemaTextError) as result:
                filter_schema_dump(text)
            self.assertNotIn('private', str(result.exception))

    def test_project_loader_can_load_the_scanner_without_private_reads(self):
        path = Path(__file__).with_name('verify-candidate-schema-chain.py')
        spec = importlib.util.spec_from_file_location('schema_text_chain_test', path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        loaded = module.load_module('schema_text_dynamic_test', 'postgres_schema_text.py')
        self.assertEqual("SELECT 'ok';", loaded.filter_schema_dump("SELECT 'ok';"))

    def test_reparsed_array_casts_do_not_change_literal_values(self):
        first = "value = ANY ((ARRAY['A'::character varying,'B'::character varying])::text[])"
        second = "value = ANY (ARRAY[('A'::character varying)::text,('B'::character varying)::text])"
        self.assertEqual(('A', 'B'), sql_literal_values(first))
        self.assertEqual(sql_literal_values(first), sql_literal_values(second))

    def test_catalogue_newlines_are_values_and_never_normalized(self):
        self.assertNotEqual(sql_literal_values("value !~ '[\r\u2028]'"),
                            sql_literal_values("value !~ '[\n\n]'"))

    def test_escape_catalogue_literals_decode_utf8_bytes_and_unicode(self):
        self.assertEqual(('é', '\u2028', "one'two", 'line\r\n'),
                         sql_literal_values(r"E'\303\251',E'\u2028','one''two',E'line\r\n'"))
        self.assertEqual(('é',), sql_literal_values(r"E'\xc3\xa9'"))

    def test_standard_and_escape_backslashes_have_different_values(self):
        self.assertEqual(('\\n',), sql_literal_values(r"'\n'"))
        self.assertEqual(('\n',), sql_literal_values(r"E'\n'"))

    def test_escaped_and_doubled_quotes_can_coexist(self):
        self.assertEqual(("a''b",), sql_literal_values(r"E'a\'''b'"))


if __name__ == '__main__':
    unittest.main()
