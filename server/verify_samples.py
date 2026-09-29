"""Opt-in live verification using the private images in testImg.

Requires Pillow and a valid .env. Prints only type/date/count summaries.
"""

import base64
import io
import json
from pathlib import Path

from PIL import Image

import server


def main() -> None:
    server.load_env()
    summaries = []
    for path in sorted((server.ROOT / "testImg").glob("*.jpg")):
        image = Image.open(path)
        image.thumbnail((2600, 2600))
        output = io.BytesIO()
        image.save(output, format="JPEG", quality=85)
        result = server.analyze({"image_base64": base64.b64encode(output.getvalue()).decode(), "mime_type": "image/jpeg"})
        summaries.append({
            "file": path.name,
            "type": result["report_type"],
            "sample_date": result["sample_date"],
            "items": len(result["observations"]),
            "items_with_reference": sum(bool(item["reference"]) for item in result["observations"]),
        })
    print(json.dumps(summaries, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
