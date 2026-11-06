#!/usr/bin/env python3
"""One-time admin repair, runnable standalone; never reads or modifies signing secrets."""
import json
import subprocess

REPO = 'robdor80/Cuadrante_App'
ENVIRONMENTS = ('android-release', 'android-dev', 'android-publish')


def api(path, method='GET', body=None):
    command = ['gh', 'api', '--method', method, f'repos/{REPO}/{path}']
    if body is not None:
        command += ['--input', '-']
    result = subprocess.run(command, input=None if body is None else json.dumps(body), capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f'{method} /repos/{REPO}/{path} falló. Se requiere una sesión gh de la cuenta administradora con Administration: write (o PAT clásico repo); no la conexión de Codex. Sin reintentos automáticos.')
    return json.loads(result.stdout or 'null')


def main():
    # Clearing protection must never release an old pending publication as a side effect.
    for status in ('waiting', 'in_progress', 'queued', 'pending', 'requested'):
        if api(f'actions/workflows/android-distribute.yml/runs?status={status}&per_page=1')['workflow_runs']:
            raise SystemExit('Hay una distribución activa. No se han cambiado los entornos ni publicado nada.')
    # Read all configuration before starting mutations, preserving branch policies exactly.
    environments = {name: api(f'environments/{name}') for name in ENVIRONMENTS}
    custom = {name: api(f'environments/{name}/deployment_protection_rules')['custom_deployment_protection_rules']
              for name in ENVIRONMENTS}
    for name, previous in environments.items():
        api(f'environments/{name}', 'PUT', dict(wait_timer=0, reviewers=[], prevent_self_review=False,
            deployment_branch_policy=previous.get('deployment_branch_policy')))
        for rule in custom[name]:
            api(f"environments/{name}/deployment_protection_rules/{rule['id']}", 'DELETE')
    for name in ENVIRONMENTS:
        current = api(f'environments/{name}')
        rules = api(f'environments/{name}/deployment_protection_rules')['custom_deployment_protection_rules']
        if any(rule['type'] != 'branch_policy' for rule in current['protection_rules']) or rules:
            raise SystemExit(name + ': todavía hay protecciones obligatorias; no se ha publicado nada.')
        if current.get('deployment_branch_policy') != environments[name].get('deployment_branch_policy'):
            raise SystemExit(name + ': la política de ramas no coincide.')
        print(name + ': verificado sin revisores, temporizadores ni aprobaciones; secretos conservados.')


if __name__ == '__main__':
    main()
