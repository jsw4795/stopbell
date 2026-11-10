import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from prepare_seoul_route_endpoints import read_bundle


class TopisBundleTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.bundle = Path(directory.name)
        (self.bundle / 'collected-at.txt').write_text('2026-11-10T12:46:25Z')

    def page(self, index, total, route_id, start='정릉', end='개포동'):
        (self.bundle / f'page-{index}.json').write_text(json.dumps({
            'paginationInfo': {'currentPageNo': index, 'totalRecordCount': total,
                               'recordCountPerPage': 1},
            'TotalRows': total,
            'rows': [{'routId': route_id, 'routName': '143',
                      'stnFirst': start, 'stnLast': end}],
        }))

    def test_exact_ids_and_unavailable_names(self):
        self.page(1, 2, '100100022')
        self.page(2, 2, '100100023', end='  ')
        rows, digest, _ = read_bundle(self.bundle)
        self.assertEqual(rows, [('100100022', '143', '정릉', '개포동', 1),
                                ('100100023', '143', '정릉', None, 2)])
        self.assertEqual(len(digest), 64)

    def test_rejects_duplicate_id_even_across_pages(self):
        self.page(1, 2, '100100022')
        self.page(2, 2, '100100022', end='다른 종점')
        with self.assertRaisesRegex(ValueError, 'Duplicate official route ID'):
            read_bundle(self.bundle)

    def test_rejects_incomplete_pages(self):
        self.page(1, 2, '100100022')
        with self.assertRaisesRegex(ValueError, 'Incomplete TOPIS bundle'):
            read_bundle(self.bundle)


if __name__ == '__main__':
    unittest.main()
