import unittest
import urllib.error
from check_registry_version import check

CONFIG = {"owner": "example", "images": {"go": "app"}, "version": "v1.0.0"}


class RegistryGuardTests(unittest.TestCase):
    def test_existing_tag_on_later_page_blocks_publication(self):
        def fetch(path):
            return [{}] * 100 if path.endswith("page=1") else [{"metadata": {"container": {"tags": ["v1.0.0"]}}}]
        with self.assertRaises(ValueError):
            check(CONFIG, fetch)

    def test_permission_or_network_failures_never_approve_publication(self):
        for status in (401, 403, 429, 503):
            def fetch(path):
                raise urllib.error.HTTPError(path, status, "unavailable", {}, None)
            with self.assertRaises(urllib.error.HTTPError):
                check(CONFIG, fetch)

    def test_new_package_and_unused_tag_are_allowed(self):
        def missing(path):
            raise urllib.error.HTTPError(path, 404, "not found", {}, None)
        check(CONFIG, missing)
        check(CONFIG, lambda _: [{"metadata": {"container": {"tags": ["v0.9.0"]}}}])


if __name__ == "__main__":
    unittest.main()
