import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("firewall", Path(__file__).parents[2] / "deployment/helpers/firewall.py")
firewall = importlib.util.module_from_spec(spec)
spec.loader.exec_module(firewall)


class FirewallInput(unittest.TestCase):
    def test_invalid_input_never_runs_commands(self):
        state = {"generation": 1, "rules": [], "apps": {}, "pending": None}
        for change in (
            {"action": "add", "generation": 1, "protocol": "tcp;id", "decision": "allow", "port": "80"},
            {"action": "add", "generation": 1, "protocol": "tcp", "decision": "allow", "port": "80;id"},
            {"action": "add", "generation": 1, "protocol": "tcp", "decision": "allow", "port": "65536"},
            {"action": "add", "generation": 1, "protocol": "tcp", "decision": "allow", "port": "80", "source": "$(id)"},
            {"action": "add", "generation": 0},
            {"action": "confirm", "id": "not-pending"},
        ):
            with self.subTest(change=change), patch.object(firewall, "run") as run:
                with self.assertRaises(ValueError):
                    firewall.handle(change, state)
                run.assert_not_called()

    def test_app_subnet_must_be_private_and_ipv4(self):
        for network in ("8.8.8.0/24", "fd00::/64"):
            with self.assertRaises(ValueError):
                firewall.handle({"action": "app", "id": "a" * 36, "subnet": network, "ports": []}, {"apps": {}})
