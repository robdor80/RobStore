"""No requests or publications: validate the single-order adapter."""
import json
import subprocess
import unittest
from unittest.mock import patch
import robpublisher_dispatch as r


class DispatchTests(unittest.TestCase):
    def test_single_order_has_no_manual_publication_fields(self):
        with patch.object(r.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(r.dispatch('robdor80/RobStore')['status'], 'accepted')
        run.assert_called_once()
        args, kwargs = run.call_args
        self.assertIn('repos/robdor80/RobStore/actions/workflows/android-distribute.yml/dispatches', args[0])
        self.assertEqual(json.loads(kwargs['input']), dict(ref='main', inputs=dict(channel='release')))

    def test_failure_does_not_repeat_publication_order(self):
        with patch.object(r.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)) as run:
            with self.assertRaises(ValueError):
                r.dispatch('robdor80/RobStore', 'dev')
        run.assert_called_once()

    def test_invalid_target_never_dispatches(self):
        for repo, channel in [('someone/App', 'release'), ('robdor80/App', 'bad')]:
            with patch.object(r.subprocess, 'run') as run, self.assertRaises(ValueError):
                r.dispatch(repo, channel)
            run.assert_not_called()
