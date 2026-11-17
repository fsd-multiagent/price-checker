"""Static acceptance tests for story 3-1 (Terraform for the production VM).

The orchestrator's `tests` gate runs only pytest (T-8), so these checks read
`infra/`, the terraform-plan workflow and the ADRs as text. The plan-level
assertions live in `infra/tests/plan.tftest.hcl` (mocked provider); the last
test runs them when a local `terraform` and an initialised `infra/` exist.
Test names carry the acceptance criterion IDs (T-1).
"""

from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
INFRA = ROOT / "infra"
WORKFLOW = ROOT / ".github" / "workflows" / "terraform-plan.yml"
ADR_DIR = ROOT / "docs" / "adr"

# Same pattern the workflow's AD-14 step greps for.
AD14_FORBIDDEN = re.compile(
    r"azurerm_network_watcher|flow_log|azurerm_monitor_diagnostic_setting"
    r"|azurerm_monitor_data_collection|azurerm_log_analytics"
    r"|azurerm_virtual_machine_extension|^[ \t]*boot_diagnostics([ \t]|\{|$)",
    re.MULTILINE,
)


def _tf_sources() -> dict[str, str]:
    files = sorted([*INFRA.glob("*.tf"), *INFRA.glob("*.tftpl")])
    assert files, "infra/ has no Terraform sources"
    return {p.name: p.read_text(encoding="utf-8") for p in files}


def _all_tf() -> str:
    return "\n".join(_tf_sources().values())


def _workflow() -> str:
    return WORKFLOW.read_text(encoding="utf-8")


def _ssh_adr() -> Path:
    adrs = sorted(ADR_DIR.glob("[0-9][0-9][0-9][0-9]-*.md"))
    matches = [p for p in adrs if "ssh" in p.name.lower()]
    assert len(matches) == 1, f"expected exactly one SSH ADR, found {[p.name for p in matches]}"
    return matches[0]


# --- 3.1.1 ---------------------------------------------------------------


def test_ac_3_1_1_defines_exactly_one_linux_vm() -> None:
    tf = _all_tf()
    assert len(re.findall(r'resource\s+"azurerm_linux_virtual_machine"', tf)) == 1
    assert not re.search(r'resource\s+"azurerm_(windows_)?virtual_machine"', tf)
    assert not re.search(r'resource\s+"azurerm_(linux|windows)_virtual_machine_scale_set"', tf)
    vm_block = tf.split('resource "azurerm_linux_virtual_machine"', 1)[1].split("\nresource ", 1)[0]
    multiplied = re.search(r"^\s*(count|for_each)\s*=", vm_block, re.MULTILINE)
    assert not multiplied, "the VM must not be multiplied"


def test_ac_3_1_1_default_region_is_eu_and_validated() -> None:
    variables = _tf_sources()["variables.tf"]
    block = variables.split('variable "location"', 1)[1].split("\nvariable ", 1)[0]
    assert re.search(r'default\s*=\s*"westeurope"', block)
    allowed = re.findall(r'"([a-z]+)"', block.split("contains([", 1)[1].split("]", 1)[0])
    assert allowed and "westeurope" in allowed
    non_eu = {"eastus", "westus", "uksouth", "ukwest", "switzerlandnorth", "norwayeast"}
    assert not non_eu & set(allowed), "only EU regions may be allowed (I-3)"


def test_ac_3_1_1_public_ip_has_dns_label_and_fqdn_output() -> None:
    tf = _all_tf()
    pip = tf.split('resource "azurerm_public_ip" "app"', 1)[1].split("\nresource ", 1)[0]
    assert re.search(r"domain_name_label\s*=\s*var\.dns_label", pip)
    assert re.search(r'output\s+"fqdn"[^}]*azurerm_public_ip\.app\.fqdn', tf, re.DOTALL)


def test_ac_3_1_1_nsg_has_no_standing_ssh_allow() -> None:
    tf = _all_tf()
    rules = re.findall(r"security_rule\s*\{(.*?)\n\s*\}", tf, re.DOTALL)
    assert rules, "the NSG rules must be inline (ADR 0018)"
    allow_ports = set()
    for rule in rules:
        if re.search(r'access\s*=\s*"Allow"', rule):
            allow_ports.add(re.search(r'destination_port_range\s*=\s*"([^"]+)"', rule).group(1))
    assert allow_ports == {"80", "443"}
    separate = re.search(r'resource\s+"azurerm_network_security_rule"', tf)
    assert not separate, "no separate rule resources"


def test_ac_3_1_1_nsg_guards_the_vm_subnet_and_nic_uses_the_public_ip() -> None:
    tf = _all_tf()
    assoc = tf.split('resource "azurerm_subnet_network_security_group_association"', 1)[1]
    assoc = assoc.split("\nresource ", 1)[0]
    assert re.search(r"subnet_id\s*=\s*azurerm_subnet\.app\.id", assoc)
    assert re.search(
        r"network_security_group_id\s*=\s*azurerm_network_security_group\.app\.id", assoc
    )
    nic = tf.split('resource "azurerm_network_interface" "app"', 1)[1].split("\nresource ", 1)[0]
    assert re.search(r"subnet_id\s*=\s*azurerm_subnet\.app\.id", nic)
    assert re.search(r"public_ip_address_id\s*=\s*azurerm_public_ip\.app\.id", nic)
    vm = tf.split('resource "azurerm_linux_virtual_machine"', 1)[1].split("\nresource ", 1)[0]
    assert re.search(r"network_interface_ids\s*=\s*\[azurerm_network_interface\.app\.id\]", vm)


def test_ac_3_1_1_ci_plans_on_pull_requests_and_never_applies() -> None:
    wf = _workflow()
    assert re.search(r"^on:\s*\n(\s+.*\n)*?\s+pull_request:", wf, re.MULTILINE)
    assert re.search(r"terraform plan\b", wf)
    assert re.search(r"run:\s*terraform test\b", wf)
    changes = re.search(r"terraform\s+(apply|destroy)\b", wf)
    assert not changes, "CI must never change infrastructure (I-2)"
    assert "id-token: write" in wf, "plan authenticates over OIDC (I-5)"


def test_ac_3_1_1_ci_pins_actions_to_commit_shas() -> None:
    uses = re.findall(r"uses:\s*(\S+)", _workflow())
    assert uses
    for ref in uses:
        pinned = re.fullmatch(r"[\w.-]+/[\w.-]+@[0-9a-f]{40}", ref)
        assert pinned, f"{ref} is not pinned to a SHA (S-12)"


# --- 3.1.2 ---------------------------------------------------------------


def test_ac_3_1_2_infra_enables_no_flow_logs_or_diagnostics() -> None:
    hits = [
        f"{name}:{text[: m.start()].count(chr(10)) + 1}: {m.group(0).strip()}"
        for name, text in _tf_sources().items()
        for m in AD14_FORBIDDEN.finditer(_strip_comments(text))
    ]
    assert hits == [], f"AD-14 forbids resources that could hold client IPs: {hits}"


def test_ac_3_1_2_ad14_pattern_catches_forbidden_blocks() -> None:
    samples = [
        'resource "azurerm_network_watcher_flow_log" "x" {}',
        'resource "azurerm_monitor_diagnostic_setting" "x" {}',
        "  boot_diagnostics {}",
        'resource "azurerm_log_analytics_workspace" "x" {}',
    ]
    for s in samples:
        assert AD14_FORBIDDEN.search(s), s


def test_ac_3_1_2_ci_enforces_the_ad14_check() -> None:
    wf = _workflow()
    step = wf.split("AC 3.1.2", 1)
    assert len(step) == 2, "the workflow must have a step for AC 3.1.2"
    body = step[1].split("- name:", 1)[0]
    needles = (
        "flow_log",
        "azurerm_network_watcher",
        "azurerm_monitor_diagnostic_setting",
        "boot_diagnostics",
    )
    for needle in needles:
        assert needle in body
    assert "exit 1" in body


def _strip_comments(text: str) -> str:
    return "\n".join(re.sub(r"(^|\s)#.*$", "", line) for line in text.splitlines())


# --- 3.1.3 ---------------------------------------------------------------


def test_ac_3_1_3_ssh_restriction_has_an_adr_from_the_template() -> None:
    adr = _ssh_adr().read_text(encoding="utf-8")
    template = (ADR_DIR / "0000-template.md").read_text(encoding="utf-8")
    for heading in re.findall(r"^## .+$", template, re.MULTILINE):
        assert heading in adr, f"ADR misses template section {heading!r} (D-2)"
    assert re.search(r"^- \*\*Status:\*\* (Proposed|Accepted)", adr, re.MULTILINE)
    assert "3.1" in adr


def test_ac_3_1_3_ssh_adr_is_indexed() -> None:
    adr = _ssh_adr()
    index = (ADR_DIR / "README.md").read_text(encoding="utf-8")
    number = adr.name[:4]
    assert re.search(rf"^\| \[{number}\]\({re.escape(adr.name)}\) \|", index, re.MULTILINE)


def test_ac_3_1_3_infra_cites_the_ssh_adr() -> None:
    number = _ssh_adr().name[:4]
    assert f"ADR {number}" in _tf_sources()["main.tf"]


# --- 3.1.1 / 3.1.2 plan-level assertions ----------------------------------


@pytest.mark.skipif(
    shutil.which("terraform") is None or not (INFRA / ".terraform").is_dir(),
    reason="needs local terraform and `terraform init -backend=false` in infra/; CI runs it",
)
def test_ac_3_1_1_and_3_1_2_terraform_test_passes() -> None:
    result = subprocess.run(
        ["terraform", "test", "-no-color"],
        cwd=INFRA,
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "0 failed" in result.stdout
