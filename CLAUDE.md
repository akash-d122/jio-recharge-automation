# CLAUDE.md — JioPOS Quick Recharge Automation

## Project
Build a separate Android companion app for a Jio retailer to reduce repetitive work for common ₹19 prepaid recharges through the official JioPOS Plus app.

This is an automation-assistance project, not a replacement for JioPOS Plus.

## Reference source
Primary decompiled JioPOS Plus source is under:
`reference/jiopos-decompiled/`

Known APK:
- package: `com.jio.jpp1`
- observed version: `2.4.0`

Known relevant classes:
- `com.ril.rpos.DIBLoginActivity`
- `com.ril.rposcentral.fragments.TopUpRechargeFragment`
- `RPOSDeviceSellFragment`
- `RechargeReactActivity`

Known native login controls:
- `edtUserName`
- `edtPassword`
- `btnLogin`

Known native recharge controls:
- `rRecharge`
- `edtSearchType`
- `edtConfirmJioNumber`
- `btnSubmit`

Treat decompiled IDs/classes as hints. The runtime AccessibilityNodeInfo tree on the physical device is the source of truth.

Do targeted source inspection. Do not ingest/analyze the entire decompiled app unnecessarily.

## Architecture — mandatory
The companion app must NOT:
- modify, patch, re-sign, clone, or redistribute JioPOS Plus;
- bypass Jio authentication;
- extract/decrypt JioPOS credentials;
- recreate private Jio backend/recharge APIs;
- fabricate transaction requests;
- bypass payment/transaction safeguards.

The official JioPOS Plus app must remain responsible for authentication, customer lookup, plan retrieval, transaction creation, Jio communication, payment and recharge completion.

Preferred architecture:
`Quick Recharge UI -> Android AccessibilityService -> official JioPOS Plus`

Do not replace this with direct/private API calls.

## Credentials/security
Never hard-code, store, log, extract, decrypt, or transmit JioPOS credentials, OTPs, tokens, or secrets.

Login priority:
1. Existing authenticated JioPOS session.
2. Android Autofill/password manager, if supported.
3. Manual login fallback.

The companion app may detect/focus the login UI but must not require access to the password value.

## Known login behavior
JioPOS login is native via `DIBLoginActivity`. Authentication involves more than username/password and may include device/session/location/application information. Therefore do not recreate authentication.

JioPOS has internal remember-credential/encrypted storage. Do not access it.

## Known recharge behavior
Native flow:
`Login -> Recharge -> mobile number -> mobile number confirmation -> search/select plan -> Buy Plan -> Continue -> Cash -> completion`

Plan data separates recharge code/description/amount. Searching for `19` is not sufficient; validate that the selected plan's actual amount is exactly ₹19.

JioPOS also contains `RechargeReactActivity`; configuration may route recharge into a React/WebView implementation. Do not assume native recharge UI always appears.

## Accessibility philosophy
Build a state machine, not a recorded macro.

Selector priority:
1. resource ID
2. strong/exact visible text
3. content description
4. structural relationships
5. coordinates only as a documented last resort

Never rely on fixed sleeps when a state/node can be awaited. If the expected state cannot be identified, stop safely.

## Human confirmation
Before the final financially consequential recharge action, require explicit human confirmation showing:
- target mobile number
- intended plan
- actual amount

Do not implement unattended recharge.

## Safe failure
Stop for:
- unknown Activity/state
- unexpected dialog
- login failure/session expiry
- customer lookup failure
- plan unavailable
- amount mismatch
- mobile-number mismatch
- network error
- inaccessible node
- JioPOS update
- ambiguous transaction status

Never automatically retry when the transaction may already have succeeded.

## Milestones

### M1 — Accessibility Inspector ONLY
Build a diagnostic Android app that:
- uses AccessibilityService;
- detects `com.jio.jpp1`;
- records foreground package and Activity/class where available;
- traverses AccessibilityNodeInfo;
- records class, text, content description, resource ID, clickable, enabled, editable, focused, password flag where available, and bounds;
- exports a diagnostic report;
- never persists credential values;
- performs NO JioPOS actions.

STOP after M1.

### M2 — Login/navigation
After runtime inspection:
- detect logged-in vs login state;
- support manual login;
- test Autofill compatibility without accessing credential values;
- detect successful authentication;
- navigate to Recharge;
- no recharge completion.

### M3 — Prepare ₹19
- receive target 10-digit number;
- enter it into both JioPOS fields;
- search/select intended plan;
- validate actual amount == ₹19;
- validate target number where possible;
- proceed through Buy Plan/Continue;
- stop before final completion.

### M4 — Controlled completion
Only after M3 is reliable:
- navigate to Cash through normal UI;
- show final confirmation;
- require explicit confirmation;
- complete via normal JioPOS UI;
- detect success/failure;
- never silently retry ambiguous transactions.

### M5 — Reliability
Add timeouts, state recovery, emergency stop, safe logging, diagnostics and JioPOS-version-change detection.

## Current task
M1 ONLY.

Before coding:
1. Inspect project structure.
2. Inspect only relevant decompiled files.
3. Separate confirmed facts from assumptions.
4. Explain uncertainty.
5. Build the diagnostic AccessibilityService.

Do NOT implement:
- actual recharge automation;
- credential storage;
- automatic credential submission;
- Cash completion;
- direct Jio API access.

After M1:
- build debug APK;
- fix compile errors;
- provide exact physical-device installation/testing steps;
- state what was actually verified;
- do not claim runtime behavior was verified until tested on the physical phone.

## Quality gate
M1 passes only when the APK builds, AccessibilityService enables, JioPOS is detected, login/home/recharge screens can be captured, relevant nodes are identifiable, the report exports, and no credentials/secrets appear in the report.

## Technology
Prefer Kotlin, modern Android APIs, minimal dependencies, clear state-machine design, explicit timeouts, structured non-sensitive logs, and local-only MVP.

Do not add AI/LLM functionality.
