import pathlib
import unittest


ANDROID_APP = pathlib.Path(__file__).resolve().parents[2]
MAIN_ACTIVITY = ANDROID_APP / "app/src/main/kotlin/ai/mobilecore/MainActivity.kt"
SERVICE = ANDROID_APP / "app/src/main/kotlin/ai/mobilecore/service/MobileCoreService.kt"
MANIFEST = ANDROID_APP / "app/src/main/AndroidManifest.xml"


class ForegroundServiceContractTest(unittest.TestCase):
    def test_visible_activity_does_not_create_repeated_fgs_start_obligations(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")

        self.assertNotIn("startForegroundService(", source)
        self.assertIn("startService(intent)", source)

    def test_every_service_delivery_reasserts_foreground_before_model_work(self):
        source = SERVICE.read_text(encoding="utf-8")
        on_start = source.split("override fun onStartCommand", 1)[1].split(
            "private fun broadcastModelLoadState", 1
        )[0]

        self.assertIn("promoteToForeground(", on_start)
        self.assertIn("backend.loadModel(", on_start)
        self.assertLess(
            on_start.index("promoteToForeground("),
            on_start.index("backend.loadModel("),
        )
        self.assertIn("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC", source)

    def test_manifest_declares_data_sync_foreground_service(self):
        source = MANIFEST.read_text(encoding="utf-8")

        self.assertIn("android.permission.FOREGROUND_SERVICE_DATA_SYNC", source)
        self.assertIn('android:foregroundServiceType="dataSync"', source)


if __name__ == "__main__":
    unittest.main()
