"""Pure guard tests: selecting V55 is explicit and cannot silently accept an adjacent schema."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location('publication_business', Path(__file__).with_name('verify-report-publication-business.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Refused(Exception):
    pass


class Connection:
    def __init__(self, objects):
        self.objects = objects

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def cursor(self):
        return self

    def execute(self, *_args):
        pass

    def fetchall(self):
        return [(value,) for value in self.objects]


class ActiveBaselineTest(unittest.TestCase):
    def validate(self, version, expected, absent=False, objects=(True,) * 5, success=True):
        snapshot = {'history': [(index, str(index), success) for index in range(1, version + 1)],
                    'candidate_objects_absent': absent}
        chain = SimpleNamespace(active_snapshot=lambda *_args: snapshot, Refused=Refused)
        pg = SimpleNamespace(connection=lambda *args, **kwargs: Connection(objects))
        return module.active_baseline(pg, chain, None, expected)

    def test_explicit_versions_require_exact_sequence_and_matching_objects(self):
        self.validate(50, 50, absent=True)
        self.validate(55, 55)
        for actual, expected in ((50, 55), (55, 50), (54, 55), (56, 55)):
            with self.assertRaises(Refused):
                self.validate(actual, expected)

    def test_missing_object_or_failed_history_or_unsupported_version_is_rejected(self):
        for kwargs in ({'objects': (True, False, True, True, True)}, {'success': False}):
            with self.assertRaises(Refused):
                self.validate(55, 55, **kwargs)
        with self.assertRaises(Refused):
            self.validate(50, 50, absent=False)
        with self.assertRaises(Refused):
            self.validate(56, 56)


if __name__ == '__main__':
    unittest.main()
