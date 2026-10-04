"""Pure text/path tests; no service, account, network or acceptance evidence is simulated as real."""
import copy
import contextlib
import io
import json
from unittest.mock import patch
import contract_progress as progress
from pathlib import Path
import tempfile
import unittest
from contract_progress import build_report, ProgressError, TRACKER

class ContractProgressTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'docs').mkdir()
        (self.root / 'docs/proof.md').write_text('Synthetic path-existence input only')
        (self.root / TRACKER.parent).mkdir(parents=True)
        self.document = {'requirement_count': 2, 'requirements': [{'id':'M1-1','title':'One','scope':'Original scope','source':{'locator':'T00R001'}},{'id':'M7-0','title':'Two','scope':'Optional pending decision','source':{'locator':'T00R002'}}]}
        self.text = '|M1-1|One|Original scope|T00R001|TC-M1-1| | |Partial [reference](../../docs/proof.md)|\n|M7-0|Two|Optional pending decision|T00R002|TC-M7-0| | | |\n### TC-M1-1 One\n执行状态：NOT_RUN；证据待填\n### TC-M7-0 Two\n执行状态：NOT_RUN；证据待填\n'
    def report(self, text=None, document=None):
        return build_report(document or self.document, self.text if text is None else text, self.root, expected_count=2)
    def test_notes_do_not_mean_completion_or_drop_optional_scope(self):
        r=self.report();self.assertEqual(r['requirement_count'],2);self.assertEqual(r['rows_with_progress_notes'],1);self.assertEqual(r['rows_without_progress_notes'],1);self.assertEqual(r['declared_acceptance_status_counts'],{'NOT_RUN':2});self.assertFalse(r['completion_verified']);self.assertFalse(r['progress_notes_are_completed_requirements']);self.assertEqual(r['requirements'][1]['id'],'M7-0')
    def test_duplicate_source_identity_is_rejected(self):
        d=copy.deepcopy(self.document);d['requirements'][1]=d['requirements'][0]
        with self.assertRaises(ProgressError):self.report(document=d)
    def test_missing_or_duplicate_table_row_is_rejected(self):
        lines=self.text.splitlines()
        for text in ['\n'.join(lines[1:]),self.text+'\n'+lines[0]]:
            with self.assertRaises(ProgressError):self.report(text)
    def test_changed_title_scope_locator_or_acceptance_target_is_rejected(self):
        for old,new in [('One|','Other|'),('Original scope|','Other scope|'),('T00R001|','T00R999|'),('TC-M1-1|','TC-M7-0|')]:
            with self.assertRaises(ProgressError):self.report(self.text.replace(old,new,1))
    def test_missing_unknown_or_duplicate_acceptance_status_is_rejected(self):
        for text in [self.text.replace('执行状态：NOT_RUN；证据待填','',1),self.text.replace('NOT_RUN','MAGIC',1),self.text.replace('### TC-M7-0','执行状态：PASS\n### TC-M7-0',1)]:
            with self.assertRaises(ProgressError):self.report(text)
    def test_duplicate_or_unknown_acceptance_section_is_rejected(self):
        for suffix in ['\n### TC-M1-1 Duplicate\n执行状态：PASS','\n### TC-M9-9 Unknown\n执行状态：PASS']:
            with self.assertRaises(ProgressError):self.report(self.text+suffix)
    def test_link_existence_is_checked_without_verifying_its_contents(self):
        r=self.report();self.assertEqual(r['requirements'][0]['local_references'],['docs/proof.md']);self.assertFalse(r['linked_evidence_contents_or_authenticity_verified'])
        with self.assertRaises(ProgressError):self.report(self.text.replace('proof.md','missing.md'))
    def test_escape_private_runtime_and_file_urls_are_rejected(self):
        for target in ['../../../../outside.md','../../.local/private.json','file:///outside.md','../../deploy/private/config.json']:
            with self.assertRaises(ProgressError):self.report(self.text.replace('../../docs/proof.md',target))
    def test_external_links_are_not_fetched_or_certified(self):
        r=self.report(self.text.replace('../../docs/proof.md','https://example.invalid/proof'));self.assertEqual(r['requirements'][0]['external_references_unchecked'],1);self.assertEqual(r['requirements'][0]['local_references'],[]);self.assertFalse(r['external_references_fetched'])
    def test_fenced_and_commented_fake_completion_does_not_count(self):
        hidden='\n```text\n### TC-M1-1 Fake\n执行状态：DONE\n```\n<!--\n### TC-M7-0 Fake\n执行状态：PASS\n-->'
        self.assertEqual(self.report(self.text+hidden)['declared_acceptance_status_counts'],{'NOT_RUN':2})
    def test_pass_or_done_declarations_still_are_not_verified_acceptance(self):
        r=self.report(self.text.replace('NOT_RUN','DONE'));self.assertEqual(r['project_declaration_state'],'DECLARATIONS_REQUIRE_EVIDENCE_REVIEW');self.assertFalse(r['completion_verified']);self.assertEqual(r['declared_acceptance_status_counts'],{'DONE':2})
    def test_count_mismatch_and_unclosed_markup_fail_closed(self):
        d=copy.deepcopy(self.document);d['requirement_count']=1
        with self.assertRaises(ProgressError):self.report(document=d)
        for text in [self.text+'\n```',self.text+'\n<!--']:
            with self.assertRaises(ProgressError):self.report(text)

    def test_strict_document_shape_and_integer_count(self):
        for document in [[], None, {**self.document, 'requirement_count': 2.0}]:
            with self.assertRaises(ProgressError):build_report(document,self.text,self.root,expected_count=2)
    def test_credential_bearing_external_link_is_not_copied(self):
        with self.assertRaises(ProgressError):self.report(self.text.replace('../../docs/proof.md','https://user:SYNTHETIC@example.invalid/proof'))
    def test_symlink_outside_repository_is_rejected_before_read(self):
        link=self.root/'docs/link.md';link.symlink_to(self.root.parent/'unrelated-not-read.md')
        with self.assertRaises(ProgressError):self.report(self.text.replace('proof.md','link.md'))
    def test_check_mode_never_overwrites_a_stale_index(self):
        output=self.root/'index.json';output.write_text('old')
        with patch.object(progress,'OUTPUT',output),patch.object(progress,'generate',return_value=json.dumps(self.report())+'\n'),patch('sys.argv',['contract_progress','--check']),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(progress.main(),1)
        self.assertEqual(output.read_text(),'old')
    def test_write_mode_creates_exact_deterministic_index_without_certifying_completion(self):
        output=self.root/'index.json';expected=json.dumps(self.report())+'\n'
        with patch.object(progress,'OUTPUT',output),patch.object(progress,'generate',return_value=expected),patch('sys.argv',['contract_progress','--write']),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(progress.main(),0)
        self.assertEqual(output.read_text(),expected);self.assertFalse(json.loads(expected)['completion_verified'])

if __name__ == '__main__':
    unittest.main()
