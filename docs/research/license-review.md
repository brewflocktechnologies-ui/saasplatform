# Licence and reuse review

Task 6 of Plan 01 (research and architecture). Written 2026-10-27. It records what the licence files in the four reference checkouts say, what third-party material they contain, our rule for using them, the open question about our own licence, and the sign-off checklist for Plan 07's final pass. It is an engineering reading of the files in the checkouts, not legal advice; the owner should have a lawyer confirm before any release outside this workspace.

Companion document: [reference-comparison.md](reference-comparison.md). Evidence notes: [chatwoot.md](evidence/chatwoot.md), [rocketchat.md](evidence/rocketchat.md), [zulip.md](evidence/zulip.md). Licence inventory by file: workspace-inventory.md section 7.

**Sources.** Local files only. Citations are `path:line` relative to the root of the checkout named in the first row of the section 2 table (full path `D:\BYTEFLOCK\RESEARCH\Allocationservice-research\<directory>`), so a bare `LICENSE:5-7` in the Chatwoot column is Chatwoot's file; our own files are relative to `D:\BYTEFLOCK\saasplatform`. External documentation consulted: none.

## 1. The rule

**Concepts and original code only.** The three reference repositories (and Live Helper Chat) are sources of ideas, vocabulary, failure modes and test cases. Everything in `saasplatform` is written by us. No block of code, SQL, configuration, markup, test, stylesheet or prose, and no asset (image, icon, font, sound, locale file, logo), is copied from a reference repository, and no visual styling is lifted from one, unless an explicit review (section 6) has approved that specific copy and recorded it (section 7).

| Case | Rule |
|---|---|
| Describing a behaviour, a data model or an algorithm in our own words, and citing the reference by `path:line` | Allowed. This is what the evidence notes and the comparison matrix do. |
| Writing our own code for a concept we saw (a sequence counter, a unique key, a lease) | Allowed. Write it from our requirement and the overview decisions, not by retyping the reference. |
| Copying any block, even short, or any asset or stylesheet from MIT or Apache-2.0 code | Needs the explicit review of section 6 first. |
| Copying, porting line by line, or "re-typing" anything under an Enterprise directory | Not allowed, with or without review. Those terms forbid copying and distribution outside a subscription (section 2). We describe Enterprise behaviour only as an observed fact and design our own. |
| A verbatim block of more than 10 lines in any file of `saasplatform` | A failure of the final sign-off (section 8, check 1) unless the register in section 7 holds an approved entry for it. |

Ten lines is the tripwire the sign-off can test mechanically. It is not a safe harbour: a shorter copy of something distinctive still needs the review.

## 2. What each repository's licence says

| | Chatwoot | Rocket.Chat | Zulip | Live Helper Chat |
|---|---|---|---|---|
| Checkout and version | `chatwoot-develop`, 4.19.1 | `Rocket.Chat-develop`, 9.0.0-develop | `zulip-main/zulip-main`, 12.0-dev | `livehelperchat-master`, no version field (changelog top entry `4.97v`) |
| Top-level licence | MIT (Expat) for everything outside `enterprise/` (`LICENSE:5-7`) | MIT (Expat) for everything outside `ee/` and `apps/meteor/ee/` (`LICENSE:5-12`) | Apache-2.0 (`LICENSE:2-3`) | Apache-2.0 (`LICENSE:1-2`) |
| Enterprise directory | `enterprise/` (677 files) under its own `enterprise/LICENSE` | `ee/` (585 files) and `apps/meteor/ee/` (408 files) under `apps/meteor/ee/LICENSE`; also the EE subtrees inside packages, `packages/*/src/ee/` (present: `packages/core-typings/src/ee/`, 2 files), which the project's guide treats as Enterprise (`docs/backend-folder-structure.md:86-91`) although the top-level `LICENSE` names only the first two (`LICENSE:5-7`) | none found within three directory levels (no `ee` or `enterprise` directory) | none found (same search) |
| Enterprise terms | Production use only with a subscription or enterprise licence (`enterprise/LICENSE:6-12` [EE-LICENCE]); modifications and patches stay the licensor's and may only be used with a subscription (`enterprise/LICENSE:12-18` [EE-LICENCE]); copying and modifying for development and testing is allowed without one (`enterprise/LICENSE:18-20` [EE-LICENCE]); otherwise copying, merging, publishing, distributing, sublicensing and selling are forbidden (`enterprise/LICENSE:22-24` [EE-LICENCE]) | Same shape: production use only with a subscription (`apps/meteor/ee/LICENSE:6-12` [EE-LICENCE]); development and testing copy allowed (`apps/meteor/ee/LICENSE:18-21` [EE-LICENCE]); copying, merging, publishing, distributing, sublicensing and selling forbidden (`apps/meteor/ee/LICENSE:22-23` [EE-LICENCE]); the EE licence applies only to parts not distributed in Community Edition, and parts served client-side as images, fonts, CSS or JavaScript-producing files are stated to be MIT (`apps/meteor/ee/LICENSE:25-31` [EE-LICENCE]) | n/a | n/a |
| Third-party components | Keep their own licences (`LICENSE:6`, `enterprise/LICENSE:34-36` [EE-LICENCE]); no third-party notices file found by name within four levels | Keep their own licences (`LICENSE:8-10`); `apps/meteor/licenses/THIRD-PARTY-LICENSES.md` lists Noto Sans fonts under SIL OFL 1.1; separate licence files in `packages/livechat/LICENSE` (MIT, 2023 Rocket.Chat; `packages/livechat/LICENSE:1-3`), `packages/storybook-dark-mode/LICENSE` (MIT-style, Andrew Lisowski 2019 and Rocket.Chat 2026; `packages/storybook-dark-mode/LICENSE:1-2`), `packages/apps-engine/src/definition/LICENSE` (MIT, Rocket.Chat 2015-2023; `packages/apps-engine/src/definition/LICENSE:1-3`), `packages/fuselage/src/fonts/LICENSE-Inter.txt` (the Inter font, SIL OFL 1.1, The Inter Project Authors 2016; `packages/fuselage/src/fonts/LICENSE-Inter.txt:1-5`) and `apps/meteor/.docker/licenses/LICENSE` (MIT, Rocket.Chat 2015-2022; `apps/meteor/.docker/licenses/LICENSE:1-3`) | `NOTICE:1-3` (copyright), `NOTICE:15-18` points to `docs/THIRDPARTY`: 158 `Files:` stanzas in Debian copyright format with many licences, for example BSD-3-Clause for `confirmation/*` (`docs/THIRDPARTY:23-25`), CC-BY-SA-4.0 (`docs/THIRDPARTY:27-29`), GPL-2.0 for a tooling script marked "Not linked" (`docs/THIRDPARTY:31-34`), CC0 and CC-BY-3.0 sounds (`docs/THIRDPARTY:45-56`), Apache-2.0 emoji images (`docs/THIRDPARTY:58-60`), GPL-2.0+ for `tools/check-thirdparty` (`docs/THIRDPARTY:62-64`), ISC icons (`docs/THIRDPARTY:66-68`), Apache-2.0 Bootstrap CSS (`docs/THIRDPARTY:719-722`), Expat `marked` (`docs/THIRDPARTY:734-736`); also `confirmation/LICENSE.txt` and `web/third/marked/LICENSE` | `lhc_web/doc/LICENSE-2.0.txt`; a vendored `lhc_web/ezcomponents/LICENSE` and a `CREDITS` file per vendored component |
| Trademarks | Not addressed by the MIT text; brand assets exist in `public/brand-assets/` (three logo SVGs) | Not addressed by the MIT text | Apache-2.0 section 6: no permission to use trade names or product names except to describe origin (`LICENSE:139-142`) | Same clause (`LICENSE:138-141`) |
| Status for this project | Concepts only. No code, asset or Enterprise material used. | Concepts only. No code, asset or Enterprise material used. | Concepts only. No code or asset used. | Present in the workspace, not required by the spec, not researched, nothing used. |

Points worth knowing before anyone is tempted to copy:

- **A file path does not prove a licence, in either direction.** Chatwoot loads Enterprise modules into MIT classes when the `enterprise/` directory exists (`config/initializers/01_inject_enterprise_edition_module.rb:24-48`, `lib/chatwoot_app.rb:14-17`), and its MIT-licensed `db/schema.rb` already contains Enterprise-owned columns such as `sla_policy_id` (`db/schema.rb:1013`). Rocket.Chat's own guide says community code may import EE modules behind licence checks and that files must never cross the `ee/` boundary; the same passage names standalone EE workspaces at the repository root and `packages/*/src/ee/` subtrees as Enterprise code too (`docs/backend-folder-structure.md:86-91`). The only such subtree present is `packages/core-typings/src/ee/` (two type files); a search for directories named `ee` under `src` found no other. The behaviours that live only in Enterprise code are listed in Appendix A of each note and are marked `[EE-LICENCE]` wherever cited.
- **Rocket.Chat's tree is duplicated** (`Rocket.Chat-develop/Rocket.Chat-develop/`); the two copies carry the same licence files. Nothing cites the nested copy.
- **Zulip is not uniformly Apache-2.0.** `docs/THIRDPARTY` lists GPL-licensed tooling and Creative Commons content next to the Apache-2.0 core. Anyone who ever approves a Zulip copy must look the file up in that list first.
- **Apache-2.0 reuse carries obligations MIT does not**: keep the licence, preserve the `NOTICE` content, mark changed files. MIT requires the copyright and permission notice to travel with the copied portions. Neither obligation applies today because nothing is copied.
- **Names and logos.** Chatwoot, Rocket.Chat, Zulip and Live Helper Chat are their owners' names. We use them in documentation to cite sources, nothing else; no logo, brand colour set or product name is used in the platform.

## 3. Third-party assets in the references and our use of them

| Repository | Assets and third-party material seen | Used by us |
|---|---|---|
| Chatwoot | `public/brand-assets/` logos, `public/audio/` sounds, favicon and app icons under `public/` | none |
| Rocket.Chat | Noto Sans fonts (OFL 1.1) per `apps/meteor/licenses/THIRD-PARTY-LICENSES.md`; the Inter variable font `packages/fuselage/src/fonts/InterVariable.woff2` (SIL OFL 1.1, `packages/fuselage/src/fonts/LICENSE-Inter.txt`); icons, sounds and fonts under `apps/meteor/public/` | none |
| Zulip | Notification sounds, emoji images, icons, Bootstrap CSS, `marked` (all in `docs/THIRDPARTY`) | none |
| Live Helper Chat | Vendored `ezcomponents` PHP libraries with `CREDITS` files | none |

Our widget (`apps/chatwidget`) and dashboard (`apps/chatdashboardapp`) keep their own design (overview D18). The scan in section 8, check 1 compares the SHA-256 of every image, font, sound and SVG in `saasplatform` with those in the references.

## 4. Our own licence: open item for the owner

The platform's licence is inconsistent, and this review does not resolve it.

| Where | What it says |
|---|---|
| `package.json:4` | `"license": "MIT"` |
| `libs/contracts/openapi/platform-api.yaml:12-14` | `license: name: Proprietary`, `identifier: LicenseRef-Proprietary` |
| Repository root | No `LICENSE` file (the listing of `D:\BYTEFLOCK\saasplatform` shows none) |
| `services/modulithplatformservice/pom.xml`, `apps/*/package.json`, `libs/*/package.json` | No licence field |
| `allocation-engine` (the allocation service, a separate project) | No `LICENSE` file at its root either |

**Owner to decide:** which licence applies to the platform and the allocation service, then add the `LICENSE` file and align `package.json`, the OpenAPI `info.license` and any other manifest. Until then nobody can state what licence our own code is under.

Effect on the work: because the rule forbids copying, this open item does not block Plans 02 to 07. It does block (a) any entry in the approved-reuse register, because the notices to add depend on our own licence, and (b) any release outside this workspace.

## 5. What the research did with licensed material

- The three evidence notes and the matrix describe behaviour in our own words. Each note states that no code was copied and that Enterprise code is only described. Every Enterprise path cited is followed by `[EE-LICENCE]`, and each note's citation script checks that tag (chatwoot.md section 0 and 17, rocketchat.md section 19, zulip.md section 18; the matrix script in reference-comparison.md section 4 checks it again). Zulip has no Enterprise directory.
- The notes and the matrix paraphrase; any quotation is a short phrase or an identifier. The fenced blocks in `docs/research` hold our own commands and scripts, and the scan in section 8 (check 1) finds no block of more than 10 lines from a reference anywhere in `docs/research`.
- The reference checkouts were only read. Their file counts and newest modification times were recorded in `docs/research/reference-snapshot.txt` so Plan 07 can prove they were not modified.
- A baseline run of the scan in section 8 on 2026-10-27, after both documents of this task were written, gave: `indexed 38605 reference files, 3027093 windows, 2721 asset hashes` and `scanned 239 platform files: 0 with a verbatim block over 10 significant lines, 0 asset copies` (about seven seconds; the 239 files are every file outside the skipped directories, including these two documents). The scan found nothing to review.

## 6. How an explicit review works

A copy is considered only when original code would be clearly worse (for example a standard algorithm with one correct form). The review answers, in writing, in the register below:

1. Exactly what is copied (path and line range in the reference, and where it lands in `saasplatform`).
2. Which licence file governs that path (section 2), and confirmation that the path is not under an Enterprise directory and not listed as a third-party component with a stricter licence.
3. Why original code is not enough.
4. The notice the licence requires (MIT: copyright and permission notice; Apache-2.0: licence text, `NOTICE` content, a statement of changes; OFL fonts: licence text) and where it is added in the tree.
5. Who approved it and on what date. The owner of the platform approves; the implementer does not approve their own copy.

Nothing is copied while the owner's own licence (section 4) is undecided.

## 7. Approved-reuse register

| Date | Source (checkout path and lines) | Licence and governing file | Lands in | Why original code was not enough | Notice added | Approved by |
|---|---|---|---|---|---|---|
| (no entries as of 2026-10-27) | | | | | | |

## 8. Sign-off checklist for Plan 07's final pass

Run from `D:\BYTEFLOCK\saasplatform` after the last code change. Record the result of each check in the Plan 07 verification report.

- [ ] **1. No file in `saasplatform` contains a verbatim block of more than 10 lines from a reference repository, and no asset is byte-identical to one.** Save the Appendix block as `reuse_scan.js` outside the repository and run `node reuse_scan.js D:/BYTEFLOCK/saasplatform D:/BYTEFLOCK/RESEARCH/Allocationservice-research` (it only reads). Pass: the last line reports `0 with a verbatim block over 10 significant lines, 0 asset copies`. For each hit, open the file at the reported line and find the source with `grep -rnF "<line text>"` in the checkout; then classify it as (a) generic boilerplate such as a licence header or a standard configuration block (rewrite it anyway if it is more than trivial), (b) coincidence, or (c) a copy, which is removed and rewritten from our requirement or sent through section 6. Record each hit and its resolution. What the scan cannot see: renamed identifiers, re-encoded assets, copies from outside the four checkouts; the reviewer reads the largest new files of each plan for near-copies.
- [ ] **2. Nothing from an Enterprise directory is used.** Search the platform's non-documentation source (`services`, `apps`, `libs`, `database`, `infra`, `tests`, `scripts`, `tools`) for the path fragments `enterprise/`, the root `ee/` tree, `apps/meteor/ee`, `src/ee/` (the `packages/*/src/ee/` subtrees) and `omni-core-ee`, and for the tag `[EE-LICENCE]`. The fragment `ee/` also occurs inside ordinary words such as `tree/`, so match it as a whole path segment and judge each hit. Pass: no hit. In `docs`, every such path appears only in `docs/research` and is tagged `[EE-LICENCE]` (re-run the note scripts and `verify_matrix.js`).
- [ ] **3. No reference asset or styling in the frontends.** Check 1 covers byte-identical files. A reviewer also opens the widget and the dashboard and confirms that no logo, product name, icon set, sound, font file or colour scheme comes from Chatwoot, Rocket.Chat, Zulip or Live Helper Chat, and that every font, icon and sound in `apps/*/public` or `assets` is either original or comes from a dependency declared in the package manifests.
- [ ] **4. The approved-reuse register (section 7) is empty or every entry is complete.** Pass: no entry cites an Enterprise path, and for each entry the required notice exists in the tree (MIT: copyright and permission notice; Apache-2.0: licence, `NOTICE` content, changes stated).
- [ ] **5. Our own licence is decided and consistent** (section 4): a `LICENSE` file exists, and `package.json`, `libs/contracts/openapi/platform-api.yaml` and every other manifest agree with it. If the owner has not decided, record "open" and do not publish.
- [ ] **6. Reference checkouts unmodified.** Compare current file counts and newest modification times with `docs/research/reference-snapshot.txt` (method in workspace-inventory.md section 7). Pass: identical.
- [ ] **7. Live Helper Chat is unused.** A search for `livehelperchat` and `lhc_web` in the non-documentation source of `saasplatform` finds nothing.
- [ ] **8. Research documents still clean.** `docs/research/**` is inside check 1, so it contains no reference block over 10 lines; the citation scripts of the three notes and `verify_matrix.js` still report 0 problems.
- [ ] **9. Dependency licences listed for the owner.** Out of scope of the reference review, but part of any release: produce the licence report of the package managers for the Maven and pnpm dependencies added by Plans 02 to 07 and hand it to the owner with the decision of check 5.
- [ ] **10. Sign-off record.** Name, date, the output of check 1, and the open items (at least the one in section 4 if still open).

## Appendix. `reuse_scan.js`

Read-only. It indexes every text file of the four checkouts (the nested Rocket.Chat copy is skipped), then scans every file of the platform. A "significant line" is a line of at least 12 characters after whitespace is collapsed, so `}` and `end` do not count; a hit is 11 consecutive significant lines that are identical (after whitespace normalisation) to 11 consecutive significant lines somewhere in a reference. Files over 1 MB, lock files, minified files and source maps are skipped. Images, fonts, sounds, PDFs and SVGs are compared by SHA-256. `node_modules`, `dist`, `target`, `.git`, `.nx`, `.next`, `.superpowers` and `.archify` are not scanned in the platform. A positive control (18 copied lines from a Chatwoot model file placed in a scratch directory) is reported as a hit, and a 10-line copy is not.

```js
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const [platform, base] = process.argv.slice(2);
const K = 11;                                                     // a verbatim block is more than 10 lines
const REFS = ['chatwoot-develop', 'Rocket.Chat-develop', 'zulip-main/zulip-main', 'livehelperchat-master'];
const SKIP_DIR = new Set(['node_modules', '.git', 'dist', 'target', '.nx', '.next', 'out-tsc', 'tmp', '.superpowers', '.archify']);
const ASSET = /\.(png|jpe?g|gif|webp|ico|svg|woff2?|ttf|otf|eot|mp3|ogg|wav|mp4|webm|pdf)$/i;
const SKIP_FILE = /(-lock\.|\.lock$|\.min\.(js|css)$|\.map$|\.zip$|\.gz$|\.jar$|\.class$)/i;
const norm = l => l.replace(/\s+/g, ' ').trim();
const sig = l => l.length >= 12;                                  // drop blank and trivial lines such as "}" or "end"
function* walk(dir, nested) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (!SKIP_DIR.has(e.name) && e.name !== nested) yield* walk(path.join(dir, e.name), nested); }
    else if (e.isFile()) yield path.join(dir, e.name);
  }
}
const lineHash = s => { let a = 0x811c9dc5, b = 0x9747b28c; for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); a = Math.imul(a ^ c, 16777619); b = (Math.imul(b ^ c, 0x85ebca6b) + i) | 0; } return [a >>> 0, b >>> 0]; };
function windows(text, fn) {                                      // fn(key, first line number of the window)
  const keep = [];
  text.split(/\r?\n/).forEach((l, i) => { const n = norm(l); if (sig(n)) keep.push([lineHash(n), i + 1]); });
  for (let i = 0; i + K <= keep.length; i++) {
    let x = 0, y = 0;
    for (let j = 0; j < K; j++) { const [a, b] = keep[i + j][0]; x = (Math.imul(x, 31) + a) >>> 0; y = (Math.imul(y, 1000003) + b) >>> 0; }
    fn(x * 2097152 + (y >>> 11), keep[i][1]);
  }
}
const readText = f => { if (fs.statSync(f).size > 1e6) return null; const b = fs.readFileSync(f); return b.subarray(0, 8000).includes(0) ? null : b.toString('utf8'); };
const sha = f => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const index = new Set(), assets = new Map(); let refFiles = 0;
for (const r of REFS) {
  const root = path.join(base, r);
  if (!fs.existsSync(root)) { console.log('MISSING REF', root); continue; }
  for (const f of walk(root, r === 'Rocket.Chat-develop' ? 'Rocket.Chat-develop' : null)) {   // skip the nested duplicate tree
    refFiles++;
    if (ASSET.test(f)) { assets.set(sha(f), f); if (!f.endsWith('.svg')) continue; }
    if (SKIP_FILE.test(f)) continue;
    const t = readText(f); if (t) windows(t, k => index.add(k));
  }
}
console.log(`indexed ${refFiles} reference files, ${index.size} windows, ${assets.size} asset hashes`);
let scanned = 0, hits = 0, assetHits = 0;
for (const f of walk(platform, null)) {
  const rel = path.relative(platform, f).split(path.sep).join('/'); scanned++;
  if (ASSET.test(f)) { const h = sha(f); if (assets.has(h)) { assetHits++; console.log('ASSET COPY', rel, '==', assets.get(h)); } if (!f.endsWith('.svg')) continue; }
  if (SKIP_FILE.test(f)) continue;
  const t = readText(f); if (!t) continue;
  const seen = []; windows(t, (k, line) => { if (index.has(k)) seen.push(line); });
  if (seen.length) { hits++; console.log(`BLOCK >10 LINES: ${rel}:${seen[0]} (${seen.length} matching windows)`); }
}
console.log(`scanned ${scanned} platform files: ${hits} with a verbatim block over 10 significant lines, ${assetHits} asset copies`);
```
