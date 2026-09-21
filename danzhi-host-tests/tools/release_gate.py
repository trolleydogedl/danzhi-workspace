#!/usr/bin/env python3
"""Fail-closed release checklist. Evidence review still requires an accountable tester.
This tool never builds, signs, publishes, or fabricates test evidence.
"""
from pathlib import Path
import argparse,hashlib,json,sys
from source_hash import source_digest,ROOT
REQUIRED = [
    'android_build_lint', 'art_api26', 'art_api31', 'art_api34', 'art_api35',
    'same_key_upgrade_169', 'startup_saved_session', 'offline_timeout_recovery',
    'live_uis_mfa', 'live_jwc', 'live_mail', 'live_elearning', 'live_ecard_payload',
    'physical_ecard_gate', 'live_ehall', 'live_timetable', 'live_rooms', 'live_notifications',
]
def check(evidence_path: Path, apk: Path | None) -> list[str]:
    failures=[]
    try: evidence=json.loads(evidence_path.read_text())
    except (OSError,ValueError) as ex:return ['Evidence unavailable: '+str(ex)]
    if evidence.get('source_tree_sha256')!=source_digest():failures.append('Source digest missing or changed since tests')
    checks=evidence.get('checks',{})
    for name in REQUIRED:
        item=checks.get(name,{})
        if item.get('status')!='PASS':failures.append(name+': '+str(item.get('status','MISSING')));continue
        relative=item.get('evidence_file','')
        if not relative:failures.append(name+': missing evidence file');continue
        path=(ROOT/relative).resolve()
        if ROOT not in path.parents or not path.is_file():failures.append(name+': evidence file absent/outside project');continue
        if hashlib.sha256(path.read_bytes()).hexdigest()!=item.get('evidence_sha256'):failures.append(name+': evidence checksum mismatch')
    if apk is None or not apk.is_file():failures.append('No candidate APK supplied')
    elif hashlib.sha256(apk.read_bytes()).hexdigest()!=evidence.get('apk_sha256'):failures.append('APK checksum not tied to reviewed evidence')
    return failures
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,default=ROOT/'reports/release-evidence.json')
    parser.add_argument('--apk',type=Path)
    args=parser.parse_args();errors=check(args.evidence,args.apk)
    print(json.dumps({'release':'BLOCKED' if errors else 'CHECKLIST_COMPLETE','reasons':errors},ensure_ascii=False,indent=2))
    sys.exit(2 if errors else 0)
