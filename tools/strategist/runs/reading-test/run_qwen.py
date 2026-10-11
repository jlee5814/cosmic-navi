"""Reading test runner for local Qwen models.
usage: run_qwen.py <prompt file> <hf model> <think 0|1> <temp> <runs> <out>"""
import sys
from mlx_lm import load, generate
from mlx_lm.sample_utils import make_sampler

prompt_file, model, think, temp, runs, out = sys.argv[1], sys.argv[2], sys.argv[3] == "1", float(sys.argv[4]), int(sys.argv[5]), sys.argv[6]
q = open(prompt_file).read()
m, t = load(model)
p = t.apply_chat_template([{"role": "user", "content": q}], add_generation_prompt=True,
                          tokenize=False, enable_thinking=think)
answers = []
for i in range(runs):
    r = generate(m, t, prompt=p, max_tokens=2500 if think else 150, sampler=make_sampler(temp=temp))
    if "</think>" in r:
        r = r.split("</think>")[-1]
    answers.append(f"[run {i + 1}] {r.strip()}")
open(out, "w").write("\n\n".join(answers) + "\n")
print("\n\n".join(answers))
