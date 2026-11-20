"""LLM Chat Manager with Queen Saba persona, conversation persistence, and streaming."""
from __future__ import annotations

from collections.abc import Generator
from typing import Any

from openai import OpenAI

from server.config import API_KEY, BASE_URL, MODEL_NAME, SYSTEM_PROMPT
from server.db.database import (
    Conversation,
    create_conversation,
    get_conversation,
)

# Standard OpenAI client configured for Ollama or OpenAI-compatible endpoints
client = OpenAI(api_key=API_KEY, base_url=BASE_URL)


class ChatManager:
    """Manages chat interactions with an LLM and persists conversation history in SQLite."""

    def __init__(
        self,
        conversation: Conversation | int | None = None,
        title: str = 'RoboDesk Conversation',
        system_prompt: str = SYSTEM_PROMPT,
        model: str = MODEL_NAME,
        llm_client: OpenAI = client,
        max_history: int | None = 20,
        enable_thinking: bool = False,
    ) -> None:
        self.client = llm_client
        self.model = model
        self.system_prompt = system_prompt
        self.max_history = max_history
        self.enable_thinking = enable_thinking

        if isinstance(conversation, Conversation):
            self.conversation = conversation
        elif isinstance(conversation, int):
            found = get_conversation(conversation)
            if found is None:
                raise ValueError(f'Conversation with ID {conversation} not found')
            self.conversation = found
        else:
            self.conversation = create_conversation(title=title)

    @property
    def conversation_id(self) -> int:
        return self.conversation.id

    @property
    def title(self) -> str:
        return self.conversation.title

    def set_title(self, title: str) -> None:
        self.conversation.title = title
        self.conversation.save(only=[Conversation.title])

    def get_history(self, include_system: bool = True) -> list[dict[str, str]]:
        sys_prompt = self.system_prompt if include_system else None
        return self.conversation.to_openai_format(system_prompt=sys_prompt, limit=self.max_history)

    def ask(self, user_message: str, **kwargs: Any) -> str:
        """Send a user message, request LLM response, and record the assistant reply."""
        if self.conversation.title in ('RoboDesk Conversation', 'New Conversation'):
            clean_title = user_message.strip()[:40]
            if clean_title:
                self.set_title(clean_title)

        self.conversation.add_message(role='user', content=user_message)
        messages = self.get_history(include_system=True)

        extra_body = kwargs.pop('extra_body', {})
        if 'think' not in extra_body:
            extra_body['think'] = self.enable_thinking
        if 'enable_thinking' not in extra_body:
            extra_body['enable_thinking'] = self.enable_thinking

        response = self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            extra_body=extra_body,
            **kwargs,
        )

        reply = response.choices[0].message.content or ''
        tokens = response.usage.total_tokens if response.usage else None
        self.conversation.add_message(role='assistant', content=reply, tokens=tokens)
        return reply

    def ask_stream(self, user_message: str, **kwargs: Any) -> Generator[str, None, str]:
        """Stream LLM response chunks, accumulate full response, and persist to database."""
        if self.conversation.title in ('RoboDesk Conversation', 'New Conversation'):
            clean_title = user_message.strip()[:40]
            if clean_title:
                self.set_title(clean_title)

        self.conversation.add_message(role='user', content=user_message)
        messages = self.get_history(include_system=True)

        extra_body = kwargs.pop('extra_body', {})
        if 'think' not in extra_body:
            extra_body['think'] = self.enable_thinking
        if 'enable_thinking' not in extra_body:
            extra_body['enable_thinking'] = self.enable_thinking

        stream_response = self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            stream=True,
            extra_body=extra_body,
            **kwargs,
        )

        in_thinking = False
        full_content: list[str] = []
        for chunk in stream_response:
            delta = chunk.choices[0].delta.content if chunk.choices else None
            if delta:
                full_content.append(delta)
                if '<think>' in delta:
                    in_thinking = True
                if '</think>' in delta:
                    in_thinking = False
                    after_tag = delta.split('</think>', 1)[1]
                    if after_tag:
                        yield after_tag
                    continue
                if not in_thinking:
                    yield delta

        reply = ''.join(full_content)
        self.conversation.add_message(role='assistant', content=reply)
        return reply


def warmup_llm(prompt: str = 'سلام', max_tokens: int = 1) -> None:
    """Send a lightweight prompt to preload LLM weights into memory."""
    try:
        client.chat.completions.create(
            model=MODEL_NAME,
            messages=[{'role': 'user', 'content': prompt}],
            max_tokens=max_tokens,
        )
        print(f'[+] LLM warm-up complete ({MODEL_NAME}).')
    except Exception as exc:
        print(f'[-] Warning: Failed to warm up LLM ({MODEL_NAME}): {exc}')
