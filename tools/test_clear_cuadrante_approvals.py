import unittest
from unittest.mock import patch
import clear_cuadrante_approvals as c


class AdminRepairTests(unittest.TestCase):
    def test_removes_all_approval_types_preserving_branches_and_secrets(self):
        calls = []
        policy = dict(protected_branches=False, custom_branch_policies=True)
        def api(path, method='GET', body=None):
            calls.append((path, method, body))
            if '/runs?' in path:
                return dict(workflow_runs=[])
            if method != 'GET':
                return None
            if path.endswith('/deployment_protection_rules'):
                # One custom rule initially; empty after mutations.
                return dict(custom_deployment_protection_rules=[] if any(m == 'PUT' for _, m, _ in calls) else [dict(id=12)])
            return dict(protection_rules=[dict(type='branch_policy')], deployment_branch_policy=policy)
        with patch.object(c, 'api', side_effect=api), patch('builtins.print'):
            c.main()
        writes = [(p, m, b) for p, m, b in calls if m != 'GET']
        self.assertEqual(len(writes), 6)
        for path, method, body in writes:
            if method == 'PUT':
                self.assertEqual(body['reviewers'], [])
                self.assertEqual(body['wait_timer'], 0)
                self.assertEqual(body['deployment_branch_policy'], policy)
        self.assertFalse(any('/secrets' in p or '/variables' in p or '/releases' in p or '/dispatches' in p for p, _, _ in calls))

    def test_active_distribution_stops_before_environment_changes(self):
        with patch.object(c, 'api', return_value=dict(workflow_runs=[dict(id=1)])) as api:
            with self.assertRaises(SystemExit):
                c.main()
        api.assert_called_once()

    def test_verification_rejects_remaining_approvals(self):
        def api(path, method='GET', body=None):
            if '/runs?' in path: return dict(workflow_runs=[])
            if path.endswith('/deployment_protection_rules'): return dict(custom_deployment_protection_rules=[])
            return dict(protection_rules=[dict(type='required_reviewers')], deployment_branch_policy=None)
        with patch.object(c, 'api', side_effect=api), self.assertRaises(SystemExit):
            c.main()
