"""Migration changes wrappers/policy only, never signing identities or releases."""
import base64
import unittest
from unittest.mock import patch
import migrate_rs05 as m


class MigrationTests(unittest.TestCase):
    def test_application_without_rs05_is_skipped(self):
        with patch.object(m.setup, 'gh_api', return_value=[dict(name='README.md')]) as api:
            m.migrate('robdor80/RobGit_App')
        api.assert_called_once()

    def test_migration_preserves_keys_and_never_dispatches(self):
        calls = []
        def api(path, method='GET', data=None):
            calls.append((path, method, data))
            if method != 'GET': return {}
            if '/actions/variables' in path:
                return dict(variables=[dict(name='ANDROID_DISTRIBUTION_TOOLING_SHA'), dict(name='KEEP')])
            if '/actions/workflows/' in path:
                return dict(workflow_runs=[])
            if path.endswith('/android-distribute.yml?ref=main'):
                return dict(sha='old', content=base64.b64encode(b'old workflow').decode())
            if path.endswith('/.github/workflows?ref=main'):
                return [dict(name='android-distribute.yml')]
            if path.endswith('/.github?ref=main'):
                return [dict(name='android-distribution.json'), dict(name='workflows')]
            return [dict(name='.github')]
        with patch.object(m.setup, 'gh_api', side_effect=api), patch.object(m.setup, 'ensure_environments') as configure:
            m.migrate('robdor80/Cuadrante_App')
        configure.assert_called_once_with('robdor80/Cuadrante_App')
        writes = [(path, method, data) for path, method, data in calls if method != 'GET']
        self.assertEqual([method for _, method, _ in writes], ['PUT', 'DELETE'])
        workflow = base64.b64decode(writes[0][2]['content']).decode()
        self.assertIn('android-distribution-engine.yml@main', workflow)
        self.assertNotIn('tooling_sha', workflow)
        self.assertFalse(any('/secrets' in path or '/dispatches' in path or '/releases' in path for path, _, _ in calls))

    def test_waiting_legacy_order_is_cancelled_without_requesting_approval(self):
        with patch.object(m.setup, 'gh_api', return_value=dict(id=10, status='completed')) as api, patch.object(m.time, 'sleep'):
            m.wait_for_legacy_runs('robdor80/Cuadrante_App', [dict(id=10, status='waiting')])
        self.assertEqual(api.call_args_list[0].args, ('repos/robdor80/Cuadrante_App/actions/runs/10/cancel', 'POST'))
        self.assertFalse(any('pending_deployments' in call.args[0] for call in api.call_args_list))
