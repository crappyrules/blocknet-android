"""Validate compiled launcher resources without relying on APK ZIP entry names."""

import re


def _resources(table):
    result = {}
    current = None
    for line in table.splitlines():
        header = re.match(r"\s*resource (0x[0-9a-fA-F]+) (\w+/[\w.]+)\b", line)
        if header:
            current = header[2]
            if current in result:
                raise ValueError(f"Duplicate compiled resource {current}")
            result[current] = (header[1].lower(), {})
            continue
        file_value = re.match(r"\s*\(([^)]*)\) \(file\) (res/\S+\.xml) type=XML\s*$", line)
        if current and file_value:
            variant = file_value[1]
            if variant in result[current][1]:
                raise ValueError(f"Duplicate {current} variant {variant}")
            result[current][1][variant] = file_value[2]
    return result


def verify_launcher(resources, manifest, archive_names, dump_xmltree, sidecar=False):
    """Raise ValueError unless the packaged adaptive icon and its references work."""
    entries = _resources(resources)

    def entry(name):
        if name not in entries:
            raise ValueError(f"Compiled {name} resource is missing")
        return entries[name]

    suffix = "_offlinetest" if sidecar else ""
    launcher_name = f"mipmap/ic_launcher{suffix}"
    foreground_name = ("drawable/ic_offlinetest_launcher_foreground" if sidecar
                       else "drawable/ic_launcher_foreground")
    monochrome_name = ("drawable/ic_offlinetest_launcher_monochrome" if sidecar
                       else "drawable/ic_launcher_monochrome")
    background_name = ("color/ic_offlinetest_launcher_background" if sidecar
                       else "color/ic_launcher_background")
    launcher_id, launcher_variants = entry(launcher_name)
    foreground_id, foreground_variants = entry(foreground_name)
    monochrome_id, monochrome_variants = entry(monochrome_name)
    background_id, _ = entry(background_name)

    for attribute in ("icon", "roundIcon"):
        if re.search(rf":{attribute}\(0x[0-9a-fA-F]+\)=@{re.escape(launcher_id)}\b", manifest) is None:
            raise ValueError(f"Packaged application {attribute} does not reference {launcher_name}")
    if "android.intent.action.MAIN" not in manifest or "android.intent.category.LAUNCHER" not in manifest:
        raise ValueError("Packaged manifest has no launcher activity")
    alias_at = manifest.find("PaymentLinkActivity")
    if alias_at < 0:
        raise ValueError("Packaged manifest has no payment-link activity alias")
    alias_header = manifest[alias_at:manifest.find("E: intent-filter", alias_at)]
    wanted = "false" if sidecar else "true"
    if re.search(rf":enabled\(0x[0-9a-fA-F]+\)={wanted}\b", alias_header) is None:
        raise ValueError("Payment-link alias has the wrong enabled state")

    def packaged(name, variants, variant):
        path = variants.get(variant)
        if not path or path not in archive_names:
            raise ValueError(f"Missing packaged {name} XML for {variant or 'default'}")
        return dump_xmltree(path)

    for name, variants in (("foreground", foreground_variants), ("monochrome", monochrome_variants)):
        vector = packaged(name, variants, "")
        if "E: vector" not in vector or "E: path" not in vector:
            raise ValueError(f"Packaged launcher {name} is not a vector with artwork")

    for variant in ("anydpi", "anydpi-v33"):
        icon = packaged("adaptive icon", launcher_variants, variant)
        if not all(f"E: {element}" in icon for element in ("adaptive-icon", "background", "foreground")):
            raise ValueError(f"Packaged {variant} launcher is not a complete adaptive icon")
        if re.search(rf"E: background\s+.*?=@{re.escape(background_id)}\b", icon, re.S) is None:
            raise ValueError(f"Packaged {variant} launcher has the wrong background")
        if re.search(rf"E: foreground\s+.*?=@{re.escape(foreground_id)}\b", icon, re.S) is None:
            raise ValueError(f"Packaged {variant} launcher has the wrong foreground")
        if variant == "anydpi-v33":
            if re.search(rf"E: monochrome\s+.*?=@{re.escape(monochrome_id)}\b", icon, re.S) is None:
                raise ValueError("Packaged Android 13 launcher lacks its themed monochrome artwork")
