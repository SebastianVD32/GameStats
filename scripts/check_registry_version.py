"""Read-only preflight: never overwrite a previously published release tag."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


def check(config, fetch):
    for image in config["images"].values():
        for page in range(1, 101):
            path = f'/users/{config["owner"]}/packages/container/{urllib.parse.quote(image, safe="")}/versions?per_page=100&page={page}'
            try:
                versions = fetch(path)
            except urllib.error.HTTPError as error:
                new_package = error.code == 404 and page == 1
                error.close()
                if new_package:
                    break  # New package (an existing inaccessible package cannot be overwritten by this token).
                raise
            for version in versions:
                if config["version"] in version.get("metadata", {}).get("container", {}).get("tags", []):
                    raise ValueError(f'{image}:{config["version"]} already exists; choose a new version')
            if len(versions) < 100:
                break
        else:
            raise ValueError("Too many package versions to establish safe publication")


if __name__ == "__main__":
    config = json.loads((Path(__file__).resolve().parents[1] / "distribution/release.json").read_text())
    token = os.environ["GH_TOKEN"]  # GITHUB_TOKEN supplied only by the publishing workflow.

    def fetch(path):
        request = urllib.request.Request("https://api.github.com" + path, headers={
            "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
            "User-Agent": "GameStats-release-preflight"})
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    check(config, fetch)
    print("No visible package contains the candidate tag; publication preflight passed.")
