#!/usr/bin/env python3
"""Where the fleet's online time goes, from logs alone (no server needed).

Reads logs/bot-decisions/grind-target-*.jsonl (one row per grind target decision, per bot) and the server logs
(logs/<yyyy-mm>/<day>/cosmic-log-*.log plus logs/cosmic-log.log). A bot is grinding while its rows are at most
3 minutes apart; a gap of 3 to 90 minutes is time online but not grinding, classified by the bot's log lines inside
it (fleet ops rescue, server restart, storage mule visit, dropped errand, shop or gear trip, else travel or town);
a gap over 90 minutes is offline. Also lists every fleet ops rescue with its minutes and place.

Usage: python3 tools/bots/fleet_time_loss.py [logs_dir] [id=Name ...]
"""
import collections, datetime, glob, json, re, sys

LOGS = sys.argv[1] if len(sys.argv) > 1 and '=' not in sys.argv[1] else 'logs'
NAMES = {254: 'SipsBuddy1', 255: 'SipsBuddy2', 256: 'SipsBuddy3', 279: 'SipsBuddy26'}
for a in sys.argv[1:]:
    if '=' in a:
        k, v = a.split('=', 1)
        NAMES[int(k)] = v
GRIND_MS, OFFLINE_MS = 3 * 60_000, 90 * 60_000


def log_lines():
    files = sorted(glob.glob(LOGS + '/2026-*/*/cosmic-log-*.log')) + [LOGS + '/cosmic-log.log']
    for f in files:
        m = re.search(r'cosmic-log-(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})', f)
        start = (datetime.datetime.strptime(' '.join(m.groups()), '%Y-%m-%d %H %M %S') if m
                 else datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None))
        day, last = start.replace(hour=0, minute=0, second=0), None
        try:
            fh = open(f, errors='ignore')
        except OSError:
            continue
        for line in fh:
            t = re.match(r'(\d{2}):(\d{2}):(\d{2})', line)
            if not t:
                continue
            ts = day + datetime.timedelta(hours=int(t[1]), minutes=int(t[2]), seconds=int(t[3]))
            if last and ts < last - datetime.timedelta(hours=1):  # past midnight
                day += datetime.timedelta(days=1)
                ts += datetime.timedelta(days=1)
            last = ts
            yield int(ts.replace(tzinfo=datetime.timezone.utc).timestamp() * 1000), line.strip()


def cause(lines, same_map):
    s = ' '.join(lines)
    if 'auto rescue' in s:
        return 'frozen or stalled (fleet ops rescue)'
    if 'Thread-0] DEBUG client.Character - Attempting to save chr' in s:
        return 'server restart'
    if 'found no way' in s:
        return 'no way to grind map'
    if 'storage mules' in s:
        return 'storage mule visit'
    if 'dropped its errand' in s:
        return 'errand dropped'
    if 'gear trip' in s or 'bot-shop' in s:
        return 'shop or gear trip'
    return 'pause on the same map' if same_map else 'travel or town'


def main():
    events = collections.defaultdict(list)
    rescues = []
    for ms, line in log_lines():
        for bid, n in NAMES.items():
            if re.search(r'\b' + n + r'\b', line):
                events[bid].append((ms, line))
        r = re.search(r"(?:auto rescued|couldn't auto rescue) (\S+) \((.*?)\) (?:from|at) (.+?)(?: by |: |$)", line)
        if r:
            rescues.append((r[1], int(re.search(r'(\d+) min', r[2])[1]), r[3].strip(), r[2]))
    rows = collections.defaultdict(list)
    for f in sorted(glob.glob(LOGS + '/bot-decisions/grind-target-*.jsonl')):
        for line in open(f):
            try:
                r = json.loads(line)
            except ValueError:
                continue
            rows[r['bot']].append((r['t'], r['map'], (r.get('out') or {}).get('exp', 0) or 0))
    print('bot            online h  grinding h  exp/h grinding  not grinding (h by cause)')
    for bot, rs in sorted(rows.items(), key=lambda kv: -len(kv[1])):
        rs.sort()
        online = grind = exp = 0
        lost = collections.Counter()
        ev = events.get(bot, [])
        for a, b in zip(rs, rs[1:]):
            g = b[0] - a[0]
            if g > OFFLINE_MS:
                continue
            online += g
            if g <= GRIND_MS:
                grind += g
                exp += a[2]
                continue
            inside = [l for t, l in ev if a[0] - 60_000 <= t <= b[0] + 60_000]
            lost[cause(inside, a[1] == b[1])] += g
        if online < 30 * 60_000:
            continue
        print(f"{NAMES.get(bot, bot):<14} {online/3.6e6:8.1f} {grind/3.6e6:11.1f} {exp/max(grind/3.6e6, 1e-9):15,.0f}  "
              + ', '.join(f"{k} {v/3.6e6:.1f}" for k, v in lost.most_common()))
    print('\nfleet ops rescues (minutes flagged at rescue time):')
    by = collections.Counter()
    n = collections.Counter()
    for who, mins, where, _ in rescues:
        by[(who, where)] += mins
        n[(who, where)] += 1
    for (who, where), mins in by.most_common():
        print(f'  {who:<12} {where:<32} {mins:5d} min  {n[(who, where)]}x')
    print(f'  total {sum(by.values())} min')


if __name__ == '__main__':
    main()
