#!/usr/bin/env python3
"""Migración única de aplicaciones personales; nunca ejecuta distribuciones."""
import argparse
import base64
import time
from pathlib import Path
import android_owner_setup as setup

REPOSITORIES = ('robdor80/Cuadrante_App', 'robdor80/RobStore', 'robdor80/RobGit_App', 'robdor80/Rob_Apps')


def wait_for_legacy_runs(repo, runs):
    for run in runs:
        deadline = time.monotonic() + 1200
        cancelled = False
        while run['status'] != 'completed':
            if run['status'] in ('waiting', 'action_required') and not cancelled:
                # Retire the old blocked order instead of requiring its human approval.
                setup.gh_api(f"repos/{repo}/actions/runs/{run['id']}/cancel", 'POST')
                cancelled = True
            setup.require(time.monotonic() < deadline, repo + ': la ejecución anterior sigue activa; no se ha modificado su publicación')
            time.sleep(2)
            run = setup.gh_api(f"repos/{repo}/actions/runs/{run['id']}")


def migrate(repo, check=False):
    setup.require(repo.startswith('robdor80/') and repo.count('/') == 1, 'Repositorio personal no válido')
    # Absent RS-05 configuration means the application has not been onboarded yet.
    listing = setup.gh_api(f'repos/{repo}/contents?ref=main')
    if not any(entry['name'] == '.github' for entry in listing):
        print(repo + ': todavía sin RS-05; sin cambios.')
        return
    root = setup.gh_api(f'repos/{repo}/contents/.github?ref=main')
    if not any(entry['name'] == 'android-distribution.json' for entry in root):
        print(repo + ': todavía sin RS-05; se incorporará con la plantilla nueva durante su alta.')
        return
    central = repo == 'robdor80/RobStore'
    wrapper = Path(__file__).with_name('templates').joinpath('android-distribute.yml.template').read_text()
    if central:
        wrapper = wrapper.replace('robdor80/RobStore/.github/workflows/android-distribution-engine.yml@main',
                                  './.github/workflows/android-distribution-engine.yml')
    current = next((entry for entry in root if entry['name'] == 'workflows'), None)
    workflows = setup.gh_api(f'repos/{repo}/contents/.github/workflows?ref=main') if current else []
    old = next((entry for entry in workflows if entry['name'] == 'android-distribute.yml'), None)
    old_file = setup.gh_api(f'repos/{repo}/contents/.github/workflows/android-distribute.yml?ref=main') if old else None
    changed = not old_file or base64.b64decode(old_file['content']).decode() != wrapper
    if check:
        print(repo + ': ' + ('actualizar entrada' if changed else 'entrada actualizada') + '; revisar entornos sin tocar secretos.')
        return
    # Do not release a waiting run as a side effect of migration.
    if old:
        runs = setup.gh_api(f'repos/{repo}/actions/workflows/android-distribute.yml/runs?per_page=100')['workflow_runs']
        wait_for_legacy_runs(repo, runs)
    setup.ensure_environments(repo)
    if changed:
        body = dict(message='RS-05: distribución automática con motor central', branch='main',
                    content=base64.b64encode(wrapper.encode()).decode())
        if old_file:
            body['sha'] = old_file['sha']
        setup.gh_api(f'repos/{repo}/contents/.github/workflows/android-distribute.yml', 'PUT', body)
    variables = setup.gh_api(f'repos/{repo}/actions/variables')['variables']
    for variable in variables:
        if variable['name'] in ('ANDROID_DISTRIBUTION_TOOLING_SHA', 'ANDROID_DISTRIBUTION_ENABLED'):
            setup.gh_api(f"repos/{repo}/actions/variables/{variable['name']}", 'DELETE')
    print(repo + ': migración completada; claves y secretos conservados; no se ha publicado nada.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', action='append', help='Por defecto procesa las cuatro aplicaciones personales')
    parser.add_argument('--check', action='store_true', help='Comprobar sin escribir cambios')
    args = parser.parse_args()
    failures = []
    for repo in args.repository or REPOSITORIES:
        try:
            migrate(repo, args.check)
        except ValueError as error:
            failures.append(repo)
            print(repo + ': no se pudo completar la migración: ' + str(error))
    setup.require(not failures, 'Migraciones pendientes: ' + ', '.join(failures))


if __name__ == '__main__':
    main()
