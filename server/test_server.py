import base64
import unittest
from unittest.mock import patch

import server


class RecognitionTests(unittest.TestCase):
    def test_extracts_rows_and_keeps_missing_reference_blank(self):
        response = {
            "choices": [{"message": {"content": '{"report_type":"血常规","sample_date":"2026-09-10","report_date":"","institution":"","observations":[{"name":"白细胞计数","value":"4.29","unit":"*10^9/L","reference":"","flag":""}]}'}}],
            "model": "qwen3.7-flash",
        }
        image = base64.b64encode(b"fake image bytes").decode()
        with patch.object(server, "post_model", return_value=response):
            result = server.analyze({"image_base64": image, "mime_type": "image/jpeg"})
        self.assertEqual(result["report_type"], "血常规")
        self.assertEqual(result["sample_date"], "2026-09-10")
        self.assertEqual(result["observations"][0]["reference"], "")

    def test_rejects_non_tabular_result(self):
        with self.assertRaises(ValueError):
            server.normalize_report({"report_type": "其他", "observations": "missing"})

    def test_rejects_invalid_image(self):
        with self.assertRaises(ValueError):
            server.analyze({"image_base64": "bad***", "mime_type": "image/jpeg"})

    def test_normalizes_report_date_without_time(self):
        self.assertEqual(server.normalize_date("2026-09-24 07:58:34"), "2026-09-24")
        self.assertEqual(server.normalize_date("2026年9月10日"), "2026-09-10")


if __name__ == "__main__":
    unittest.main()
