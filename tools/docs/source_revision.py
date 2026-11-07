"""Keep published guides and repository links on the same source revision."""

import os
import re


def on_config(config):
    revision = os.environ.get("CARAML_DOCS_REVISION", "main")
    if not re.fullmatch(r"main|[0-9a-f]{40}", revision):
        raise ValueError("CARAML_DOCS_REVISION must be main or a full commit SHA")
    config.extra["source_revision"] = revision
    config.extra["source_url"] = f"{config.repo_url}/tree/{revision}"
    branch = os.environ.get("CARAML_DOCS_BRANCH", "main")
    config.edit_uri = f"edit/{branch}/docs/"
    return config


def on_page_markdown(markdown, *, page, config, files):
    revision = config.extra["source_revision"]
    markdown = markdown.replace(
        f"{config.repo_url}/blob/main/", f"{config.repo_url}/blob/{revision}/"
    )
    markdown = markdown.replace(
        f"[Source]({config.repo_url})", f"[Source]({config.extra['source_url']})"
    )
    if revision != "main" and page.file.src_uri == "getting-started.md":
        markdown = markdown.replace(
            "cd CaraML\n",
            "cd CaraML\n"
            "# Match the revision used to build these guides\n"
            f"git checkout --detach {revision}\n",
            1,
        )
    return markdown
