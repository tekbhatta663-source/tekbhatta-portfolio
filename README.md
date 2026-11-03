# TEKBHATTA Portfolio

A premium personal portfolio built with Next.js 14 (App Router), TypeScript and hand-written CSS.
Dark, light and Neon AI themes, a multilingual assistant (**Tek AI**), and a contact form.

## What is real and what needs configuration

| Feature | Status |
| --- | --- |
| Homepage, all sections, themes, animations, responsive layout | Working |
| Theme preference saved across visits | Working (localStorage) |
| Tek AI chat UI, typing indicator, language picker | Working |
| Tek AI answers | Works in **demo mode** from `data/knowledge.json`. Set `OPENAI_API_KEY` for LLM answers |
| Contact form validation | Working |
| Contact form delivery | Requires `RESEND_API_KEY` + `CONTACT_TO_EMAIL`. Without them the API returns 503 and the UI says delivery is not configured |
| SEO metadata, sitemap, robots.txt, 404 | Working |
| Admin dashboard + database CMS | **Not built.** Schema supplied in `supabase/schema.sql`; see below |

Nothing claims to work that does not. There are no placeholder certificates, fake testimonials or invented employers.

## Run locally

```bash
npm install
cp .env.example .env.local   # optional
npm run dev                  # http://localhost:3000
```

```bash
npm run build && npm start   # production check
```

## Environment variables

See `.env.example`.

- `OPENAI_API_KEY`, `OPENAI_MODEL` — enables real LLM answers in Tek AI. Without it, Tek AI answers from the local knowledge base only.
- `RESEND_API_KEY`, `CONTACT_TO_EMAIL` — enables contact form email delivery via Resend.
- `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY` — only for the database CMS.

Keys are read server-side only, inside `app/api/*`. Nothing secret is exposed to the browser.

## Editing content

All copy lives in `data/content.ts`: profile, bio, journey, skills, projects, education, certifications, socials.
Skill bars are **self-assessed** values you set yourself; there are no invented percentages.

Tek AI's knowledge lives in `data/knowledge.json`. Every answer must trace back to a `facts` entry.
Add or edit facts there and the assistant's grounded answers change immediately.

## Deploy

**Vercel (recommended)**

1. Push this folder to a GitHub repository.
2. Import it at vercel.com/new.
3. Add the environment variables you need.
4. Deploy.

**Any Node host**: `npm run build` then `npm start`.

After deploying, update the domain in `app/layout.tsx` (`metadataBase`), `app/sitemap.ts` and `app/robots.ts`.

## Optional: database CMS

`supabase/schema.sql` defines `projects`, `skills`, `education`, `certifications`, `media`,
`contact_messages`, `chatbot_kb` and `site_settings`, with Row Level Security enabled and
public read policies for published rows. Admin writes go through a server-side service-role
route, so the admin surface is never public. Creating an admin dashboard on top of that schema
is a separate build step and is not included here.

## Structure

```
app/            layout, page, API routes, sitemap, robots, 404
components/     Nav, ThemeToggle, Reveal, ChatWidget, ChatTeaser, ContactForm
data/           content.ts (all copy), knowledge.json (Tek AI facts)
public/         favicon
supabase/       schema.sql
```
