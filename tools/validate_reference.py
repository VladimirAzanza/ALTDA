"""
Reference validator (Python) for ADD (АЦР) education programs, contract v1.1.

This is an executable specification: the Go service ports these rules 1:1.
Usage: python validate_reference.py program.schema.json example_program.json
       (a file with a top-level "variants" key is validated as a skeleton-step
        response against $defs/skeletonResponse)
Requires: pip install jsonschema

Hours move in steps of 0.5, so every comparison is done in half-hours (x2, as
integers) to avoid floating-point surprises.
"""
import json
import sys

from jsonschema import Draft202012Validator

HOUR_KEYS = ["lecture", "practice", "lab", "selfStudy", "assessment"]
ACTIVITY_HOURS_KEY = {"lecture": "lecture", "practice": "practice", "lab": "lab",
                      "selfStudy": "selfStudy", "control": "assessment"}
STATUS_ORDER = ["intake", "clarifying", "skeleton_proposed", "skeleton_approved",
                "content_generating", "content_ready", "validated", "exported"]
MIN_ASSESSMENTS_PER_OUTCOME = 2   # tests + seminar tasks + final attestation
MINUTES_PER_ACADEMIC_HOUR = 45
WEIGHT_TOLERANCE = 0.01


def half(x):
    """Hours -> integer half-hours."""
    return round(x * 2)


def check(checks, rule_id, category, ok, message, path="", related=None, severity="error"):
    checks.append({
        "ruleId": rule_id, "category": category, "severity": severity,
        "passed": ok, "message": message, "path": path, "relatedIds": related or [],
    })


def status_at_least(prog, status):
    return STATUS_ORDER.index(prog["status"]) >= STATUS_ORDER.index(status)


def check_row(checks, h, path, label):
    contact = h["lecture"] + h["practice"] + h["lab"]
    check(checks, "HOURS_CONTACT", "hours", half(h["contact"]) == half(contact),
          f"{label}: contact={h['contact']}, expected {contact}", path)
    total = contact + h["selfStudy"] + h["assessment"]
    check(checks, "HOURS_ROW_SUM", "hours", half(h["total"]) == half(total),
          f"{label}: total={h['total']}, expected {total}", path)


def sum_hours(rows):
    return {k: sum(r[k] for r in rows) for k in HOUR_KEYS + ["contact", "total"]}


def hours_equal(a, b, keys):
    return all(half(a[k]) == half(b[k]) for k in keys)


def all_topics(cur):
    return [t for m in cur["modules"] for t in m["topics"]]


def outcome_ids_of(competency):
    return [o["id"] for key in ("knowledge", "skills", "abilities") for o in competency[key]]


def validate_hours(prog, checks):
    cur = prog["curriculum"]
    for mi, m in enumerate(cur["modules"]):
        mpath = f"/curriculum/modules/{mi}"
        check_row(checks, m["hours"], f"{mpath}/hours", m["id"])
        for ti, t in enumerate(m["topics"]):
            check_row(checks, t["hours"], f"{mpath}/topics/{ti}/hours", t["id"])
        s = sum_hours([t["hours"] for t in m["topics"]])
        # Module-level assessment may live only on the module row
        s["assessment"] = m["hours"]["assessment"]
        s["total"] = sum(s[k] for k in HOUR_KEYS)
        check(checks, "HOURS_TOPICS_EQ_MODULE", "hours",
              hours_equal(s, m["hours"], HOUR_KEYS + ["total"]),
              f"{m['id']}: sum of topics {s} != module {m['hours']}", f"{mpath}/hours", [m["id"]])

    fa = prog["finalAttestation"]["hours"]
    check_row(checks, fa, "/finalAttestation/hours", "Final attestation")
    grand = sum_hours([m["hours"] for m in cur["modules"]] + [fa])
    check(checks, "HOURS_TOTALS_FIELD", "hours",
          hours_equal(grand, cur["totals"], list(grand)),
          f"declared totals {cur['totals']} != computed {grand}", "/curriculum/totals")
    check(checks, "HOURS_EQ_REQUESTED", "hours",
          half(grand["total"]) == half(prog["header"]["totalHours"]),
          f"Sum {grand['total']} h != requested {prog['header']['totalHours']} h", "/header/totalHours")

    # Minimum practice share: (practice + lab) / total
    min_share = (prog["request"].get("params") or {}).get("minPracticeShare")
    if min_share is not None and grand["total"] > 0:
        share = (grand["practice"] + grand["lab"]) / grand["total"]
        check(checks, "HOURS_PRACTICE_SHARE", "hours", share + 1e-9 >= min_share,
              f"Practice share {share:.0%} < required {min_share:.0%}", "/curriculum/totals")

    # Activities must reproduce the topic's hours kind by kind
    for mi, m in enumerate(cur["modules"]):
        for ti, t in enumerate(m["topics"]):
            acts = t.get("activities")
            if acts is None:
                continue
            by_key = {k: 0 for k in HOUR_KEYS}
            for a in acts:
                by_key[ACTIVITY_HOURS_KEY[a["kind"]]] += a["hours"]
            check(checks, "HOURS_ACTIVITIES_EQ_TOPIC", "hours",
                  hours_equal(by_key, t["hours"], HOUR_KEYS),
                  f"{t['id']}: activities {by_key} != topic hours "
                  f"{ {k: t['hours'][k] for k in HOUR_KEYS} }",
                  f"/curriculum/modules/{mi}/topics/{ti}/activities", [t["id"]])


def validate_calendar(prog, checks, topic_ids):
    cal = prog.get("calendar")
    if not cal:
        return
    sessions = cal["sessions"]
    numbers = [s["number"] for s in sessions]
    check(checks, "CALENDAR_SESSION_NUMBERS", "consistency",
          numbers == list(range(1, len(sessions) + 1)),
          f"Session numbers must be 1..{len(sessions)} in order, got {numbers}", "/calendar/sessions")
    bad = sorted({t for s in sessions for t in s["topicIds"]} - topic_ids)
    check(checks, "CONSISTENCY_DANGLING_TOPIC", "consistency", not bad,
          f"Calendar references non-existent topics: {bad}", "/calendar/sessions", bad)
    # Self-study is not scheduled in sessions (assumption: confirm with the ADD template)
    totals = prog["curriculum"]["totals"]
    scheduled = sum(s["hours"] for s in sessions)
    expected = totals["total"] - totals["selfStudy"]
    check(checks, "CALENDAR_HOURS", "hours", half(scheduled) == half(expected),
          f"Sessions add up to {scheduled} h, expected {expected} h (total minus self-study)",
          "/calendar/sessions", severity="warning")


def validate_coverage(prog, checks, outcomes):
    cur = prog["curriculum"]
    taught = {oid for t in all_topics(cur) for oid in t["outcomeIds"]}

    # Assessment tools per outcome: every question, seminar task and the final attestation counts once
    tools = {oid: 0 for oid in outcomes}
    assessment = prog.get("assessment") or {}
    uses = []
    for test in assessment.get("tests", []):
        uses += [q["outcomeIds"] for q in test["questions"]]
    uses += [s["outcomeIds"] for s in assessment.get("seminarTasks", [])]
    uses.append(prog["finalAttestation"].get("outcomeIds", []))
    for ids in uses:
        for oid in set(ids):
            if oid in tools:
                tools[oid] += 1

    assessed_stage = status_at_least(prog, "content_ready") or bool(assessment)
    for oid in sorted(outcomes):
        check(checks, "COVERAGE_OUTCOME_TAUGHT", "coverage", oid in taught,
              f"{oid} does not appear in any topic", related=[oid])
        if assessed_stage:
            check(checks, "COVERAGE_OUTCOME_ASSESSED", "coverage", tools[oid] >= MIN_ASSESSMENTS_PER_OUTCOME,
                  f"{oid} has {tools[oid]} assessment tool(s), needs >= {MIN_ASSESSMENTS_PER_OUTCOME}",
                  related=[oid])

    # Outcome IDs must start with their competency's ID
    for c in prog["competencies"]:
        bad = [i for i in outcome_ids_of(c) if i.split(".")[0] != c["id"]]
        check(checks, "CONSISTENCY_OUTCOME_PREFIX", "consistency", not bad,
              f"{c['id']}: outcomes with a foreign prefix: {bad}", related=bad)

    # Dangling outcome references
    referenced = set(taught)
    for ids in uses:
        referenced.update(ids)
    for lec in prog.get("lectures", []):
        referenced.update(lec.get("outcomeIds", []))
    dangling = sorted(referenced - outcomes)
    check(checks, "CONSISTENCY_DANGLING_OUTCOME", "consistency", not dangling,
          f"Non-existent outcomes referenced: {dangling}", related=dangling)


def validate_references(prog, checks, topic_ids):
    cur = prog["curriculum"]
    assessment = prog.get("assessment") or {}

    topic_refs = {t["topicId"] for t in prog.get("lectures", [])}
    topic_refs |= {s["topicId"] for s in assessment.get("seminarTasks", [])}
    topic_refs |= {tid for test in assessment.get("tests", []) for q in test["questions"]
                   for tid in q.get("topicIds", [])}
    topic_refs |= {tid for s in prog.get("sources", []) for tid in s.get("topicIds", [])}
    topic_refs |= {tid for t in prog.get("tools", []) for tid in t.get("usedInTopics", [])}
    topic_refs |= {p for t in all_topics(cur) for p in t.get("prerequisites", [])}
    bad_topics = sorted(topic_refs - topic_ids)
    check(checks, "CONSISTENCY_DANGLING_TOPIC", "consistency", not bad_topics,
          f"Non-existent topics referenced: {bad_topics}", related=bad_topics)

    # Topic order: prerequisites must come earlier in the curriculum (also rules out cycles)
    position = {t["id"]: i for i, t in enumerate(all_topics(cur))}
    late = [f"{t['id']} <- {p}" for t in all_topics(cur) for p in t.get("prerequisites", [])
            if p in position and position[p] >= position[t["id"]]]
    check(checks, "ORDER_PREREQUISITES", "consistency", not late,
          f"Prerequisites that do not come earlier: {late}", related=late)

    # Methodologist references (RAG) cited but missing from request.attachments
    ref_ids = {r["id"] for r in prog["request"].get("attachments", [])}
    used_refs = {rid for t in all_topics(cur) for rid in t.get("referenceIds", [])}
    used_refs |= {rid for lec in prog.get("lectures", []) for rid in lec.get("referenceIds", [])}
    bad_refs = sorted(used_refs - ref_ids)
    check(checks, "CONSISTENCY_DANGLING_REFERENCE", "consistency", not bad_refs,
          f"Non-existent references cited: {bad_refs}", related=bad_refs)

    # Sources cited but missing from sources[]
    source_ids = {s["id"] for s in prog.get("sources", [])}
    used_src = set(prog.get("normativeBasis", []))
    used_src |= {sid for lec in prog.get("lectures", []) for sid in lec.get("sourceIds", [])}
    bad_src = sorted(used_src - source_ids)
    check(checks, "CONSISTENCY_DANGLING_SOURCE", "consistency", not bad_src,
          f"Non-existent sources cited: {bad_src}", related=bad_src)

    # Profstandard links must point to a profstandard (and labor function) listed in dpp
    if prog.get("programType") == "DPP_PK":
        standards = {p["registryRef"]: {f["code"] for f in p.get("laborFunctions", [])}
                     for p in prog["dpp"]["profStandards"]}
        bad_links = []
        for c in prog["competencies"]:
            for link in c["profStandardLinks"]:
                funcs = standards.get(link["registryRef"])
                if funcs is None or (funcs and link["laborFunctionCode"] not in funcs):
                    bad_links.append(f"{c['id']}: {link['registryRef']}/{link['laborFunctionCode']}")
        check(checks, "CONSISTENCY_PROFSTANDARD_LINK", "consistency", not bad_links,
              f"Competency links not found in dpp.profStandards: {bad_links}", related=bad_links)


def validate_content(prog, checks):
    cur = prog["curriculum"]
    assessment = prog.get("assessment") or {}
    topics = {t["id"]: t for t in all_topics(cur)}

    # Lecture plan minutes of a topic = its lecture hours x 45
    minutes = {}
    for lec in prog.get("lectures", []):
        minutes[lec["topicId"]] = minutes.get(lec["topicId"], 0) + sum(s["minutes"] for s in lec["plan"])
    for tid, mins in minutes.items():
        if tid not in topics:
            continue
        expected = round(topics[tid]["hours"]["lecture"] * MINUTES_PER_ACADEMIC_HOUR)
        check(checks, "LECTURE_MINUTES", "hours", mins == expected,
              f"{tid}: lecture plans add up to {mins} min, expected {expected} min", related=[tid])

    # Test keys
    for test in assessment.get("tests", []):
        for q in test["questions"]:
            ok, msg = test_key_ok(q)
            check(checks, "TEST_KEY", "consistency", ok, f"{test['id']}/{q['id']}: {msg}",
                  related=[q["id"]])

    # Seminar tasks: score and duration
    per_topic = {}
    for s in assessment.get("seminarTasks", []):
        total = sum(c["maxPoints"] for c in s["criteria"])
        check(checks, "TASK_MAXSCORE", "consistency", total == s["maxScore"],
              f"{s['id']}: maxScore={s['maxScore']}, criteria add up to {total}", related=[s["id"]])
        per_topic[s["topicId"]] = per_topic.get(s["topicId"], 0) + s["durationHours"]
    for tid, dur in per_topic.items():
        if tid in topics:
            h = topics[tid]["hours"]
            cap = h["practice"] + h["lab"]
            check(checks, "TASK_DURATION", "hours", half(dur) <= half(cap),
                  f"{tid}: tasks take {dur} h, topic has {cap} h of practice + lab", related=[tid])


def test_key_ok(q):
    """Check the answer key of one question."""
    t, correct = q["type"], q["correct"]
    if t == "short":
        return True, "ok"
    option_ids = [o["id"] for o in q.get("options", [])]
    if len(set(option_ids)) != len(option_ids):
        return False, "duplicate option ids"
    unknown = [c for c in correct if c not in option_ids]
    if unknown:
        return False, f"correct answers not among the options: {unknown}"
    if t in ("single", "truefalse"):
        return len(correct) == 1, f"{len(correct)} correct answers (must be exactly 1)"
    if t == "multiple":
        return (2 <= len(correct) < len(option_ids),
                f"{len(correct)} correct of {len(option_ids)} options (need 2+, and not all)")
    return True, "ok"


def validate(prog):
    checks = []
    if not status_at_least(prog, "skeleton_proposed"):
        return checks   # nothing to verify before a skeleton exists

    cur = prog["curriculum"]
    outcomes = {i for c in prog["competencies"] for i in outcome_ids_of(c)}
    topic_ids = {t["id"] for t in all_topics(cur)}

    validate_hours(prog, checks)
    validate_calendar(prog, checks, topic_ids)
    validate_coverage(prog, checks, outcomes)
    validate_references(prog, checks, topic_ids)
    validate_content(prog, checks)

    # Tools flagged as outdated (used when updating old programs)
    for t in prog.get("tools", []):
        if t.get("status") == "outdated":
            check(checks, "TOOL_OUTDATED", "consistency", False,
                  f"{t['name']} {t.get('version', '')} is outdated (current: {t.get('latestVersion', '?')}); "
                  f"topics: {t.get('usedInTopics', [])}",
                  related=t.get("usedInTopics", []), severity="warning")

    # Sources
    for i, s in enumerate(prog.get("sources", [])):
        check(checks, "SOURCE_UNVERIFIED", "sources", s["verified"],
              f"Source {s['id']}: unverified", f"/sources/{i}", [s["id"]], severity="warning")

    # Completeness
    for sec in prog.get("templateSections", []):
        check(checks, "SECTION_MISSING", "completeness", sec["status"] != "missing",
              f"Empty template section: {sec['title']}", related=[sec["key"]])

    return checks


def validate_skeleton_response(resp):
    """Code-side checks of a skeleton-step response (before the hour allocator runs)."""
    checks = []
    for v in resp["variants"]:
        key = f"variant {v['key']}"
        outcomes = {i for c in v["competencies"] for i in outcome_ids_of(c)}
        topic_pos = {t["id"]: i for i, t in enumerate(t for m in v["modules"] for t in m["topics"])}
        w = sum(m["weight"] for m in v["modules"])
        check(checks, "SKELETON_MODULE_WEIGHTS", "hours", abs(w - 1) <= WEIGHT_TOLERANCE,
              f"{key}: module weights add up to {w:.3f}, expected 1", related=[v["key"]])
        for m in v["modules"]:
            tw = sum(t["weight"] for t in m["topics"])
            check(checks, "SKELETON_TOPIC_WEIGHTS", "hours", abs(tw - 1) <= WEIGHT_TOLERANCE,
                  f"{key}/{m['id']}: topic weights add up to {tw:.3f}, expected 1", related=[m["id"]])
        used = {oid for m in v["modules"] for t in m["topics"] for oid in t["outcomeIds"]}
        check(checks, "SKELETON_DANGLING_OUTCOME", "consistency", used <= outcomes,
              f"{key}: unknown outcomes {sorted(used - outcomes)}", related=sorted(used - outcomes))
        check(checks, "COVERAGE_OUTCOME_TAUGHT", "coverage", outcomes <= used,
              f"{key}: outcomes not in any topic {sorted(outcomes - used)}", related=sorted(outcomes - used))
        late = [f"{t['id']} <- {p}" for m in v["modules"] for t in m["topics"]
                for p in t.get("prerequisites", []) if p not in topic_pos or topic_pos[p] >= topic_pos[t["id"]]]
        check(checks, "ORDER_PREREQUISITES", "consistency", not late,
              f"{key}: bad prerequisites {late}", related=late)
    return checks


def report(checks):
    fails = [c for c in checks if not c["passed"]]
    for c in fails:
        print(f"[{c['severity']}] {c['ruleId']}: {c['message']}")
    print(f"{len(checks) - len(fails)} passed, {len(fails)} with issues")
    return 1 if any(c["severity"] == "error" for c in fails) else 0


def main(schema_path, prog_path):
    with open(schema_path, encoding="utf-8") as f:
        schema = json.load(f)
    with open(prog_path, encoding="utf-8") as f:
        prog = json.load(f)

    is_skeleton = "variants" in prog
    root = ({"$schema": schema["$schema"], "$defs": schema["$defs"], "$ref": "#/$defs/skeletonResponse"}
            if is_skeleton else schema)
    errors = list(Draft202012Validator(root).iter_errors(prog))
    for e in errors:
        print("SCHEMA:", "/".join(map(str, e.absolute_path)), "-", e.message)
    if errors:
        sys.exit(1)

    sys.exit(report(validate_skeleton_response(prog) if is_skeleton else validate(prog)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
