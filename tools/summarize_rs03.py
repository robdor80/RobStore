#!/usr/bin/env python3
"""Export small, path-free audit evidence from Gradle JUnit and lint XML reports."""
import argparse
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("app/build/rs03-summary"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], text=True))
    summary = ["# Resultados extraídos de XML de Gradle", "",
               f"Generación UTC: {datetime.now(timezone.utc).isoformat()}",
               f"HEAD local: `{head}`. Árbol con cambios: {'sí' if dirty else 'no'}.", "",
               "Los recuentos proceden de `<testcase>`; una prueba omitida no cuenta como superada.", ""]
    complete = True
    totals = [0, 0, 0, 0, 0]  # executed (incl. skipped), passed, failures, errors, skipped
    for variant in ("Debug", "Release"):
        reports = sorted(Path(f"app/build/test-results/test{variant}UnitTest").glob("TEST-*.xml"))
        counts = [0] * 5
        lines = ["clase\tprueba\tresultado"]
        table = ["| Suite | Ejecutadas | Superadas | Fallos | Errores | Omitidas |",
                 "| --- | ---: | ---: | ---: | ---: | ---: |"]
        hashes = []
        for path in reports:
            root = ET.parse(path).getroot()
            suite = [0] * 5
            for case in root.findall("testcase"):
                outcome = ("FALLO" if case.find("failure") is not None else
                           "ERROR" if case.find("error") is not None else
                           "OMITIDA" if case.find("skipped") is not None else "SUPERADA")
                suite[0] += 1
                suite[{"SUPERADA": 1, "FALLO": 2, "ERROR": 3, "OMITIDA": 4}[outcome]] += 1
                lines.append(f"{case.attrib['classname']}\t{case.attrib['name']}\t{outcome}")
            counts = [a + b for a, b in zip(counts, suite)]
            table.append(f"| {root.attrib['name']} | " + " | ".join(map(str, suite)) + " |")
            hashes.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}")
        complete &= bool(reports) and counts[0] > 0 and not any(counts[2:])
        totals = [a + b for a, b in zip(totals, counts)]
        table.append("| **Total** | " + " | ".join(map(str, counts)) + " |")
        summary += [f"## {variant}", "", *table, ""]
        (args.output / f"tests-{variant.lower()}.tsv").write_text("\n".join(lines) + "\n")
        (args.output / f"xml-{variant.lower()}.sha256").write_text("\n".join(hashes) + "\n")
    summary += ["## Total de ambas variantes", "", "Ejecutadas / superadas / fallos / errores / omitidas: **" +
                " / ".join(map(str, totals)) + "**.", "", "## Lint", ""]
    lint_reports = sorted(Path("app/build/reports").glob("lint-results*.xml"))
    complete &= bool(lint_reports)
    for path in lint_reports:
        issues = ET.parse(path).getroot().findall("issue")
        errors = [i for i in issues if i.attrib["severity"] in ("Error", "Fatal")]
        complete &= not errors
        summary += [f"`{path.name}`: {len(errors)} errores, {len(issues) - len(errors)} avisos.", ""]
        for issue in issues:
            # Never export absolute paths or tool messages containing runner details.
            locations = ", ".join(Path(loc.attrib["file"]).name + ":" + loc.attrib.get("line", "?")
                                  for loc in issue.findall("location"))
            summary.append(f"- {issue.attrib['severity']}: `{issue.attrib['id']}` ({locations})")
        summary.append("")
    summary += ["Estado de extracción: " + ("completo; tests y lint satisfactorios." if complete else
                "incompleto o con incidencias; consultar los XML originales."), "",
                "Este resumen no certifica compilaciones: consultar el informe de auditoría y los pasos del workflow.", ""]
    (args.output / "resultados.md").write_text("\n".join(summary))
    print("\n".join(summary))
    return 0 if complete else 1


if __name__ == "__main__":
    raise SystemExit(main())
