---
name: kb_bot_chat_state_questions
description: How bot chat splits work between code and the model. Commands first, then a classify call that names an owner's state question for code to answer, then the normal reply with a guard. Read before touching BotLlmReplyManager, BotTurn, ReplyGuard or STATE_QUESTIONS.
type: project
---
# Chat: code owns the facts, the model owns the words

A chat line goes through these stages, and each fact has one source.

1. **Commands** (`BotChatManager.handleChat`). A matching phrase runs its command; read-only info commands
   (level, where, mesos, stats, ...) answer from game state. No model call.
2. **Classify** (`server.bots.llm.BotTurn.classify`), owner lines only, when no command matched. A short
   greedy call that sees only the line (no persona, no game state) returns JSON
   `{"fact_question", "ask"}` under a JSON schema. A named question from `BotChatManager.STATE_QUESTIONS`
   (where, doing, level, exp, mesos, stats, damage, build, skills, inventory, slots, pots, scrolls) is
   answered by that command's own report (`answerStateQuestion`), so "how rich are you" gets the same reply
   as `mesos`, and no reply call is made. A line where the sender talks about themselves and never addresses
   the bot ("im lv 50 now") is never a question, whatever the model says.
3. **Reply** (the normal chat call, unchanged), for chat and for questions the classify call missed.
4. **Guard** (`ReplyGuard`) on that reply: a first person claim of the bot's level, exp percent or mesos must
   match the character (exp within 2 points either reading, mesos within 15%). On a mismatch the owner gets
   that question's report instead; anyone else gets silence. Numbers only, not meaning.

## Why two calls, not one

One call that wrote the reply and named the question was tried first. On Qwen3-4B it tagged nearly every
chat line as a question ("gg" asked where; 26 of 72 right with the question first, 38 of 72 with the reply
first), and the replies turned into stat lists. The classify call on its own, with ten examples:

| Lines | Right | Wrong report | Missed (normal reply) | Chat tagged |
|---|---|---|---|---|
| tuning (24) | 24 | 0 | 0 | 0 |
| held out, unlike the examples (24) | 20 | 1 | 3 | 0 |

About 220 ms a call warm on the M4 Pro. A miss costs nothing new: the line gets today's reply. A wrong report
answers the wrong fact ("did u max heal yet" got the level). Measured with
`tools/llm-bench/bot_eval/state_questions.py`.

## Rules

- `STATE_QUESTIONS` holds read-only commands only; a test checks each is a read-only info query and that no
  action command can be named. Add a question there, never a second report.
- The eval reads the question names, hints and examples from the Java; only the prompt sentence is copied.
- Chat costs one extra short call per owner line no command matched; fact questions cost one call in all.
- The rewrite step from agent-of-record (model rewords the code draft) is left out on purpose: command
  replies already sound like the bot (`lv70, 7.05% to 71`).

## Serving

- SGLang applies the schema through its grammar backend. On the MLX backend that needs
  `--mlx-enable-sampling` (set in `tools/botchat/install_sglang_agent.sh`).
- The first request with a new schema compiled its grammar for about 13 s; later ones took about 0.2 s.
- A server that answers `response_format` with HTTP 400 turns classify off for the process (logged once);
  the reply and the guard still run. Ollama never classifies.
