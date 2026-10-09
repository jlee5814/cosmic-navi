"""Python mirror of the game's bot chat prompt (server.bots.llm PromptBuilder.buildChat, SituationBuilder)
and of the reply cleanup in BotLlmReplyManager. Keep in step with the Java when either changes: the
eval scores this mirror, so a prompt change in the game is only measured once it is copied here.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JOBS = {int(i): n for n, i in re.findall(r"\b([A-Z][A-Z0-9_]+)\((\d+)\)",
                                         (ROOT / "src/main/java/client/Job.java").read_text())}

RULES = ("Talk like mmo chatter: lowercase, 1 short sentence, abbreviate whenever possible, text only, "
         "use internet slang when applicable, no emoji, no speaker label. "
         "Keep replies simple. Prefer fragments over full grammar. "
         "Answer only the latest message directly. If unsure, say so briefly instead of guessing. "
         "Never say you are an ai, assistant, chatbot, or bot. ")
ACTION_RULE = ("If they ask you to do something, start with yes, no, or a quick question, then at most a few words; "
               "don't list your stats unless they ask. ")
RELATION = {
    "owner": "You are talking to your close gaming friend and party leader {s}. Be friendly and helpful.",
    "party": "You are talking to {s}, your party member. Be casual.",
    "stranger": "You are talking to {s}, a stranger you just met in the game.",
}
# Prompt switches; the defaults are what the game sends today (mesos line and ACTION_RULE shipped Oct 9).
# eval.py --variant flips them; "before-oct9" reproduces the baseline run.
OPTIONS = {"mesos": True, "header": "[your game state right now]", "unknown_rule": "", "action_rule": ACTION_RULE}
UNKNOWN_RULE = "Anything not in your game state, you don't know: say so (\"idk\", \"not sure\") instead of making it up. "
VARIANTS = {
    "baseline": {},
    "before-oct9": {"mesos": False, "action_rule": ""},
    "mesos": {"mesos": True, "action_rule": ""},
    "mesos+only_if_asked": {"mesos": True, "action_rule": "",
                            "header": "[your game state right now; mention it only if the message asks about it]"},
    "mesos+only_if_asked+unknown_rule": {
        "mesos": True, "header": "[your game state right now; mention it only if the message asks about it]",
        "unknown_rule": UNKNOWN_RULE, "action_rule": ""},
    "mesos+action_rule": {"mesos": True, "action_rule": ACTION_RULE},
    "mesos+action_rule+unknown_rule": {"mesos": True, "action_rule": ACTION_RULE, "unknown_rule": UNKNOWN_RULE},
}

GEAR_WORDS = re.compile(
    r"\b(equip\w*|gear|eq|wear\w*|weapon|staff|wand|claw|sword|axe|bow|crossbow|gun|knuckle|dagger|"
    r"spear|polearm|shield|armor|hat|helm\w*|glove\w*|shoe\w*|boots?|overall|top|bottom|cape|earring\w*|items?)\b", re.I)
SKILL_WORDS = re.compile(r"\b(skills?|sp|max(ed)?|spells?|build|attacks?|spam\w*|buffs?)\b", re.I)


def job_label(job_id):
    name = JOBS.get(job_id)
    if name is None:
        return "adventurer"
    name = re.sub(r"[0-9]", "", name.lower()).replace("_", " ").strip()
    name = re.sub(r"^fp ", "fire/poison ", re.sub(r"^il ", "ice/lightning ", name))
    family = {1: "warrior", 2: "magician", 3: "bowman", 4: "thief", 5: "pirate"}.get((job_id // 100) % 10)
    return name if family is None or family in name else f"{name} ({family} class)"


def identity(f):
    return f"Your IGN is {f['name']}. You are a real human MapleStory player, level {f['level']} {job_label(f['job_id'])}."


def situation(f, question):
    lines = ["[Where you are now]"]
    if f.get("map"):
        street = f.get("street", "")
        lines.append(f"Map: {f['map']}" + (f" ({street})" if street and street.lower() != f["map"].lower() else ""))
    lines.append(f"Status: {f['status']}")
    lines.append(f"Job: {job_label(f['job_id'])}")
    level = f"Level {f['level']}"
    if f.get("exp_pct", -1) >= 0:
        level += f", EXP {f['exp_pct']}% ({100 - f['exp_pct']}% left until level {f['level'] + 1})"
    lines.append(level)
    if OPTIONS["mesos"] and "meso" in f:
        lines.append(f"Mesos: {f['meso']:,}")
    if f.get("mobs"):
        lines.append(f"Mobs around: {f['mobs']}")
    if GEAR_WORDS.search(question) and f.get("gear"):
        lines.append("Equipped: " + ", ".join(f["gear"]))
    own_skill_words = {w for s in f.get("skills", []) for w in re.split(r"\W+", re.sub(r" \d+/\d+$", "", s).lower())
                       if len(w) >= 4}
    if (SKILL_WORDS.search(question) or any(w in question.lower() for w in own_skill_words)) and f.get("skills"):
        lines.append("Skills (level/max): " + ", ".join(f["skills"]))
    return "\n".join(lines)


def build_chat(f, relation, sender, message, history=()):
    """Messages exactly as PromptBuilder.buildChat sends them (no memory summary)."""
    system = ("You are a real human MapleStory player. " + RULES + OPTIONS["unknown_rule"] + OPTIONS["action_rule"]
              + RELATION[relation].format(s=sender))
    system += "\n\n" + identity(f)
    msgs = [{"role": "system", "content": system}]
    for q, a in history:
        msgs += [{"role": "user", "content": q}, {"role": "assistant", "content": a}]
    state = situation(f, message).strip()
    msgs.append({"role": "user", "content": OPTIONS["header"] + "\n" + state + "\n\n" + message})
    return msgs


def _norm(s):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", (s or "").lower())).strip()


def _edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j - 1] + (a[i - 1] != b[j - 1]), prev[j] + 1, cur[j - 1] + 1)
        prev = cur
    return prev[len(b)]


def is_echo(message, reply):
    m, r = _norm(message), _norm(reply)
    if len(m) < 12 or not r:
        return False
    return m in r or _edit_distance(m, r) <= max(len(m), len(r)) // 5


def clean(raw, bot, sender, message, max_chars=120):
    """What the player would see: BotLlmReplyManager.sanitize, stripSpeakerLabel, then the echo guard.
    Returns None when the game would stay silent."""
    s = (raw or "")
    if "</think>" in s:
        s = s.split("</think>", 1)[1]
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^(?i:reply|bot|response|assistant)\s*:\s*", "", s)
    s = re.sub("[☀-➿\U0001f300-\U0001faff]", "", s).strip().lower()[:max_chars].strip()
    for name in (bot, sender):
        label = name.lower()
        if s.startswith(label) and s[len(label):].lstrip().startswith(":"):
            s = s[len(label):].lstrip()[1:].strip()
    if not s or is_echo(message, s):
        return None
    return s
