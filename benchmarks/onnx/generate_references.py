#!/usr/bin/env python3
"""Generate independent Hugging Face tokenizer/Jinja and Python ORT golden vectors.

Run in a virtual environment with requirements-validation.txt. References are local,
date-dependent test inputs, not downloaded model instructions executed by the app.
"""
import argparse
import datetime
import json
from pathlib import Path
from jinja2.sandbox import SandboxedEnvironment
from tokenizers import Tokenizer

MESSAGES = [{"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Explain why the sky is blue in clear, simple language."}]
TEXTS = ["Hello, world!", "Price: 12345.67; नमस्ते 🌍 café", "你好世界\n\n  test\tend",
         "Don't I'M we've 123456789", "e\u0301 café\n", "<|im_start|>user\nHi<|im_end|>\n"]


def generate(root, prompt, maximum):
    import numpy as np
    import onnxruntime as ort
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    manifest = json.loads((root / "benchmark-manifest.json").read_text())
    session = ort.InferenceSession(str(root / manifest["graph"]), options, providers=["CPUExecutionProvider"])
    tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
    generation = json.loads((root / "generation_config.json").read_text())
    eos = generation["eos_token_id"]
    eos = eos if isinstance(eos, list) else [eos]
    cache = {value.name: np.zeros([1, value.shape[1], 0, value.shape[3]], np.float32)
             for value in session.get_inputs() if value.name.startswith("past_key_values.")}
    names = {value.name for value in session.get_inputs()}
    output_names = [value.name for value in session.get_outputs()]
    next_ids, past, generated = prompt, 0, []
    for _ in range(maximum):
        length = len(next_ids)
        inputs = {"input_ids": np.array([next_ids], np.int64), **cache}
        if "attention_mask" in names:
            inputs["attention_mask"] = np.ones((1, past + length), np.int64)
        if "position_ids" in names:
            inputs["position_ids"] = np.arange(past, past + length, dtype=np.int64)[None, :]
        values = dict(zip(output_names, session.run(None, inputs)))
        token = int(values["logits"][0, -1].argmax())
        if token in eos:
            break
        generated.append(token)
        cache = {key: values[key.replace("past_key_values.", "present.")] for key in cache}
        past += length
        next_ids = [token]
    return generated, tokenizer.decode(generated)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, default=Path("build/benchmarks"))
    parser.add_argument("--with-generation", action="store_true")
    args = parser.parse_args()
    env = SandboxedEnvironment()
    env.globals["strftime_now"] = lambda fmt: datetime.datetime.now().strftime(fmt)
    def fail(message):
        raise ValueError(message)
    env.globals["raise_exception"] = fail
    for root in sorted(args.models.glob("*-onnx")):
        tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
        config = json.loads((root / "tokenizer_config.json").read_text())
        template = env.from_string(config["chat_template"])
        cases = [{"text": text, "ids": tokenizer.encode(text, add_special_tokens=False).ids} for text in TEXTS]
        (root / "tokenizer-reference.json").write_text(json.dumps(cases, indent=2))
        chats = []
        for messages in [MESSAGES, MESSAGES + [{"role": "assistant", "content": "<think>Reasoning</think>\n\nBlue light scatters."}, {"role": "user", "content": "Why?"}]]:
            prompt = template.render(messages=messages, add_generation_prompt=True,
                                     **{key: value for key, value in config.items() if key != "chat_template"})
            chats.append({"messages": messages, "prompt": prompt, "ids": tokenizer.encode(prompt, add_special_tokens=False).ids})
        (root / "chat-reference.json").write_text(json.dumps(chats, indent=2))
        if args.with_generation:
            count = 48 if root.name == "llama32-1b-onnx" else 32
            ids, text = generate(root, chats[0]["ids"], count)
            (root / "generation-reference.json").write_text(json.dumps({"ids": ids, "text": text}, indent=2))
            data = {"cases": cases, "generated_ids": ids, "text": text} if count == 32 else {"ids": ids, "text": text}
            filename = "reference.json" if count == 32 else "quality-reference.json"
            (root / filename).write_text(json.dumps(data, indent=2))
        print("Generated", root.name, flush=True)


if __name__ == "__main__":
    main()
