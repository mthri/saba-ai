"""Unit tests for conversation database models and SQLite repository."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from peewee import SqliteDatabase

from server.db.database import (
    BaseModel,
    Conversation,
    Message,
    create_conversation,
    delete_conversation,
    get_all_conversations,
    get_conversation,
    init_db,
)


class TestDatabase(unittest.TestCase):
    """Test suite verifying conversation persistence and retrieval."""

    def setUp(self) -> None:
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp_dir.name) / 'test_conv.db'
        init_db(self.db_path)

    def tearDown(self) -> None:
        self.tmp_dir.cleanup()

    def test_create_and_retrieve_conversation(self) -> None:
        conv = create_conversation(title='Test Session')
        self.assertIsNotNone(conv.id)
        self.assertEqual(conv.title, 'Test Session')

        fetched = get_conversation(conv.id)
        self.assertIsNotNone(fetched)
        self.assertEqual(fetched.title, 'Test Session')

    def test_add_messages_and_history(self) -> None:
        conv = create_conversation(title='Dialog Test')
        conv.add_message(role='user', content='سلام')
        conv.add_message(role='assistant', content='سلام! چطوری؟')

        messages = conv.get_messages()
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0].role, 'user')
        self.assertEqual(messages[0].content, 'سلام')
        self.assertEqual(messages[1].role, 'assistant')

        openai_fmt = conv.to_openai_format(system_prompt='You are an AI.')
        self.assertEqual(len(openai_fmt), 3)
        self.assertEqual(openai_fmt[0]['role'], 'system')
        self.assertEqual(openai_fmt[1]['content'], 'سلام')

    def test_delete_conversation(self) -> None:
        conv = create_conversation(title='To Delete')
        conv.add_message(role='user', content='Bye')
        cid = conv.id

        deleted = delete_conversation(cid)
        self.assertTrue(deleted)
        self.assertIsNone(get_conversation(cid))


if __name__ == '__main__':
    unittest.main()
