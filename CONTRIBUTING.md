# Contributing

## Please open an issue

This repository is a **public mirror**. Development happens on HiPay's internal GitLab, and every
change goes through an internal review and release process, so a pull request opened here cannot be
merged as it stands.

**Opening an issue is the most effective way to help us** — a bug report, a reproduction case, a
missing capability, or a rough edge in the integration. That is what reaches the team fastest and
what shapes the roadmap.

Patches are welcome all the same, and we read them. Be aware of what happens to them: the change is
re-implemented internally and lands under a maintainer's commit, so the authorship in git history
will not be yours. We credit contributors in the release notes for the change they originated. If
that arrangement does not suit you, an issue describing the problem is just as valuable to us.

Code submitted here — in a pull request or pasted into an issue — is covered by the repository's
Apache-2.0 licence (section 5), under which contributions are licensed on the same terms unless you
state otherwise.

**Only submit code you wrote yourself, or that you are otherwise entitled to submit under that
licence.** Please do not paste code owned by your employer, or taken from another product. We
re-implement what we receive and ship it to payment integrators, so we have no way to untangle it
afterwards.

## Changelog — maintainer conventions

`CHANGELOG.md` is read by **integrators**: it is rendered on the documentation site and shipped with
every release. It is not a development log — the reasoning, the alternatives and the implementation
detail belong in the tracker and in the internal design documents.

### Writing an entry

- **Two lines maximum.**
- Plain language: what changes for the integrator or the payer, not how it is built.
- **No class or method names.** The versioned integration documentation carries the API, the
  signatures and the migration steps; the changelog says what to expect and why it matters.
- **One exception — `Deprecated`:** name both the replaced and the replacing symbol. That pairing is
  the actionable information, and it is the only reason an integrator reads a deprecation notice.
- Group entries under `Added` / `Changed` / `Deprecated` / `Removed` / `Fixed`.

### Version headings

A version heading must be **exactly** `## x.y.z` — no date, no title, no suffix. The release pipeline
extracts the notes by matching that line verbatim, so `## 1.0.0 — 2026-08-10` silently yields empty
release notes. Put the date on the line below.

The publish job fails when the section for the tag is empty, so a missing or mis-titled section stops
the release rather than publishing a blank note. A release candidate therefore needs its own section
(`## 1.0.0-rc3`).

Accumulate work under `## Unreleased` and rename that heading to the version at release time.

### Where an entry ends up

| Surface | Content |
|---|---|
| GitLab Release | JIRA version link, then the `## <tag>` section |
| GitHub Release | the `## <tag>` section only — no internal tracker link on a public page |
| Documentation site | the whole file, rendered under *Changelog* |
| `build-output/` | the whole file, shipped with the release |
