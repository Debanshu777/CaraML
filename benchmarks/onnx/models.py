"""Download pinned model bundles, restore the fixture matrix and generate reference vectors."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import urllib.parse
import urllib.request


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def digest(path, lfs):
    value = hashlib.sha256() if lfs else hashlib.sha1()
    if not lfs:
        value.update(f"blob {path.stat().st_size}\0".encode())
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def download_main(argv=None):
    """Download one public, revision-pinned HF graph and sidecars, checking remote hashes.

    Usage: python3 benchmarks/run.py models download REPO GRAPH OUTPUT [--revision SHA]
    Weights belong under build/ (ignored by Git), never in the source tree."""
    parser = argparse.ArgumentParser(description=download_main.__doc__)
    parser.add_argument("repo")
    parser.add_argument("graph")
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision")
    args = parser.parse_args(argv)
    endpoint = "https://huggingface.co/api/models/" + args.repo
    if args.revision:
        endpoint += "/revision/" + args.revision
    detail = fetch_json(endpoint + "?blobs=true")
    revision = detail["sha"]
    assert len(revision) == 40 and all(c in "0123456789abcdef" for c in revision)
    sidecars = {"config.json", "tokenizer.json", "tokenizer_config.json", "generation_config.json", "special_tokens_map.json"}
    files = [file for file in detail["siblings"] if file["rfilename"] == args.graph or
             file["rfilename"].startswith(args.graph + "_data") or file["rfilename"] == args.graph + ".data" or file["rfilename"] in sidecars]
    names = {file["rfilename"] for file in files}
    assert args.graph in names
    if args.graph.endswith(".onnx"):
        assert {"config.json", "tokenizer.json"} <= names
    args.output.mkdir(parents=True, exist_ok=True)
    for file in files:
        name = file["rfilename"]
        relative = PurePosixPath(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        path = args.output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        lfs = file.get("lfs")
        expected = lfs["sha256"] if lfs else file["blobId"]
        if path.is_file() and path.stat().st_size == file["size"] and digest(path, lfs) == expected:
            print("Verified existing", name, flush=True)
            continue
        url = "https://huggingface.co/" + args.repo + "/resolve/" + revision + "/" + urllib.parse.quote(name, safe="/")
        temporary = path.with_name(path.name + ".partial")
        print("Downloading", args.repo, name, file["size"], flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as target:
            for chunk in iter(lambda: response.read(4 * 1024 * 1024), b""):
                target.write(chunk)
        assert temporary.stat().st_size == file["size"], "Incomplete download"
        assert digest(temporary, lfs) == expected, "Hash mismatch"
        temporary.replace(path)
    manifest = {"repository": args.repo, "revision": revision, "graph": args.graph,
                "downloads_at_selection": detail.get("downloads"), "files": files}
    (args.output / "benchmark-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Bundle verified", args.output, flush=True)


def restore_main(argv=None):
    """Restore the exact benchmark models from the retained revision manifests."""
    parser = argparse.ArgumentParser(description=restore_main.__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/benchmarks"))
    parser.add_argument("--only", action="append", help="Optional exact manifest name, without .json")
    args = parser.parse_args(argv)
    directory = Path(__file__).resolve().parent
    paths = sorted((directory / "results/manifests").glob("*.json"))
    if args.only:
        missing = set(args.only) - {p.stem for p in paths}
        if missing:
            parser.error("Unknown manifests: " + ", ".join(sorted(missing)))
        paths = [p for p in paths if p.stem in args.only]
    for path in paths:
        manifest = json.loads(path.read_text())
        download_main([manifest["repository"],
                        manifest["graph"], str(args.output / path.stem), "--revision", manifest["revision"]])


MESSAGES = [{"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Explain why the sky is blue in clear, simple language."}]


TEXTS = ["Hello, world!", "Price: 12345.67; नमस्ते 🌍 café", "你好世界\n\n  test\tend",
         "Don't I'M we've 123456789", "e\u0301 café\n", "<|im_start|>user\nHi<|im_end|>\n"]


def generate(root, prompt, maximum):
    from tokenizers import Tokenizer
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


def references_main(argv=None):
    """Generate independent Hugging Face tokenizer/Jinja and Python ORT golden vectors.

    Run in a virtual environment with requirements-validation.txt. References are local,
    date-dependent test inputs, not downloaded model instructions executed by the app."""
    parser = argparse.ArgumentParser(description=references_main.__doc__)
    parser.add_argument("--models", type=Path, default=Path("build/benchmarks"))
    parser.add_argument("--with-generation", action="store_true")
    args = parser.parse_args(argv)
    from jinja2.sandbox import SandboxedEnvironment
    from tokenizers import Tokenizer

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
