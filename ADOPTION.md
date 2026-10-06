# Adoption

A dated public record of how far Sieve reaches: who stars, forks, uses and contributes to it, and how much it is downloaded and visited. It is kept once a month, so that its growth, or lack of it, can be read from one place over time.

## What each column means

- **Stars, forks, watchers**: the repository's counters on GitHub on that day.
- **Contributors**: accounts with at least one commit on `main`.
- **Outside issues, outside pull requests**: opened by anyone other than the repository owner, ever, open or closed.
- **Releases, downloads**: published releases and the total downloads of their files. There are no releases yet, so downloads are `n/a`.
- **Views, clones**: GitHub's traffic counts for the 14 days before the date (total and unique). GitHub shows them only to people with push access, so the row says `n/a` when the job that wrote it could not read them; the owner can fill them in from Insights, Traffic.

## Monthly record

<!-- adoption:rows: the workflow adds each new row under the table's header -->
| Date | Stars | Forks | Watchers | Contributors | Outside issues | Outside pull requests | Releases | Downloads | Views (14 days) | Clones (14 days) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-06 | 12 | 1 | 0 | 2 | 0 | 0 | 0 | n/a | n/a | n/a |

On 2026-10-06 the first of the 12 stars is the owner's own, given when the repository was created on 2026-04-28. The two contributors are the owner and the automated account that opens pull requests under the owner's direction.

## Adopters

None are listed yet. If your organisation or project uses Sieve, add a line here with a pull request: the name, how you use it (screening, the data, the dashboard, a component), and since when.

## How the record is kept

`.github/workflows/adoption.yml` runs on the first day of each month, and on demand, reads the counters above from the GitHub API and adds a row at the top of the table, newest first. Anyone can also correct or add a row by hand in a pull request.
