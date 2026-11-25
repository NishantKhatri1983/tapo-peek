"""Saved camera accounts: Windows-only DPAPI storage (skipped elsewhere)."""

from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from companion import credentials


@unittest.skipUnless(os.name == 'nt', 'Windows DPAPI is unavailable on this platform')
class SavedAccountTests(unittest.TestCase):
    HOST = '192.0.2.77'  # TEST-NET-1; never a real household camera

    def setUp(self) -> None:
        credentials.forget_account(self.HOST)
        self.addCleanup(credentials.forget_account, self.HOST)

    def test_round_trip_returns_the_saved_account_and_stores_ciphertext(self) -> None:
        self.assertIsNone(credentials.load_account(self.HOST))
        credentials.save_account(self.HOST, 'tapo-user', 'correct horse battery staple')
        self.assertEqual(credentials.load_account(self.HOST), ('tapo-user', 'correct horse battery staple'))
        raw = credentials._saved_path(self.HOST).read_bytes()
        self.assertNotIn(b'correct horse battery staple', raw)
        self.assertNotIn(b'tapo-user', raw)

    def test_forget_is_idempotent(self) -> None:
        credentials.save_account(self.HOST, 'u', 'p')
        self.assertTrue(credentials.forget_account(self.HOST))
        self.assertFalse(credentials.forget_account(self.HOST))
        self.assertIsNone(credentials.load_account(self.HOST))

    def test_account_is_not_shared_with_another_host(self) -> None:
        credentials.save_account(self.HOST, 'u', 'p')
        self.assertIsNone(credentials.load_account('192.0.2.78'))

    def test_corrupt_or_tampered_store_fails_closed(self) -> None:
        credentials.save_account(self.HOST, 'u', 'p')
        path = credentials._saved_path(self.HOST)
        path.write_bytes(b'not a protected blob')
        with self.assertRaisesRegex(ValueError, 'could not be read'):
            credentials.load_account(self.HOST)

    def test_rejects_empty_credentials(self) -> None:
        for username, password in (('', 'p'), ('u', '')):
            with self.subTest(username=username, password=password):
                with self.assertRaises(ValueError):
                    credentials.save_account(self.HOST, username, password)


if __name__ == '__main__':
    unittest.main()
