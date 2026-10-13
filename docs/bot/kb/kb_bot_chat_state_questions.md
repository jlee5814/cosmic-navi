---
name: kb_bot_chat_state_questions
description: How bot chat splits work between code and the model. Commands first, then one structured model call that names a state question or writes a reply, then a guard on the reply. Read before touching BotLlmReplyManager, BotTurn, ReplyGuard or STATE_QUESTIONS.
type: project
---
# Chat: code owns the facts, the model owns the words

A chat line goes through three stages, and each fact has one source.

1. **Commands** (`BotChatManager.handleChat`). A matching phrase runs its command; read-only info commands
   (level, where, mesos, stats, ...) answer from game state. No model call.
2. **One structured model call** (`server.bots.llm.BotTurn`), only when no command matched. The model returns
   JSON `{"ask","reply"}`, constrained by a JSON schema (`response_format: json_schema`):
   - `ask` names a question from `BotChatManager.STATE_QUESTIONS` (where, doing, level, exp, mesos, stats,
     damage, build, skills, inventory, slots, pots, scrolls). Code answers it with that command's own report
     (`answerStateQuestion`), so "how rich are you" gets the same reply as `mesos`. Owner only; anyone else
     gets the model's reply text, as before.
   - `ask` is `none`: `reply` is the chat line.
3. **Guard** (`ReplyGuard`) on a free reply: a first person claim of the bot's level, exp percent or mesos
   must match the character (exp within 2 points either reading, mesos within 15%). On a mismatch the owner
   gets that question's report instead; anyone else gets silence. Numbers only, not meaning.

## Rules

- `STATE_QUESTIONS` holds read-only commands only; a test checks each one is a read-only info query and
  that no action command can be named. Add a question there, never a second report.
- The model never writes a state number that reaches chat unchecked: it names the question, or its reply
  passes the guard.
- One model call per unmatched line, the same as before; the commands' fast path stays free.
- The rewrite step from agent-of-record (model rewords the code draft) is left out on purpose: command
  replies already sound like the bot (`lv70, 7.05% to 71`).

## Serving

- SGLang applies the schema through its grammar backend. On the MLX backend that needs
  `--mlx-enable-sampling` (set in `tools/botchat/install_sglang_agent.sh`); without it decoding is greedy
  and ignores the grammar.
- A server that answers `response_format` with HTTP 400 turns structured turns off for the process
  (logged once), and chat falls back to free text replies, still guarded. Ollama always uses free text.
- The JSON wrapper costs about 16 tokens on top of `maxPredictTokens`.
