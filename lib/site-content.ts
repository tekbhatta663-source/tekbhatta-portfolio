import { createClient } from "@supabase/supabase-js";
import * as local from "@/data/content";

const URL = process.env.NEXT_PUBLIC_SUPABASE_URL ?? "";
const KEY = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? "";

/** True when the deployment has Supabase credentials, so edits flow live. */
export const contentIsLive = Boolean(URL && KEY);

const BAR: Record<string, number> = { Learning: 35, Practising: 55, Comfortable: 75 };

/**
 * Reads content from Supabase when configured, and otherwise uses the honest
 * static defaults in data/content.ts. Public read access is governed by row
 * level security, so the anon key is safe here.
 */
export async function getSiteContent(): Promise<{
  profile: typeof local.profile;
  skills: typeof local.skills;
  projects: typeof local.projects;
  education: typeof local.education;
  certifications: typeof local.certifications;
}> {
  const fallback = {
    profile: local.profile,
    skills: local.skills,
    projects: local.projects,
    education: local.education,
    certifications: local.certifications,
  };
  if (!URL || !KEY) return fallback;

  const sb = createClient(URL, KEY, { auth: { persistSession: false } });
  const [settings, skillRows, projectRows, educationRows, certRows] = await Promise.all([
    sb.from("settings").select("*").eq("id", 1).maybeSingle(),
    sb.from("skills").select("*").order("sort_order", { ascending: true }),
    sb.from("projects").select("*").order("sort_order", { ascending: true }),
    sb.from("education").select("*").order("start_year", { ascending: false }),
    sb.from("certifications").select("*").order("year", { ascending: false }),
  ]);

  const s = settings.data ?? {};

  const grouped = new Map<string, { level: string; items: string[] }>();
  for (const row of skillRows.data ?? []) {
    const key = (row.category as string) || "Other";
    const entry = grouped.get(key) ?? { level: (row.level as string) || "Learning", items: [] };
    entry.items.push(row.name as string);
    grouped.set(key, entry);
  }

  return {
    profile: {
      ...local.profile,
      name: s.display_name || local.profile.name,
      headline: s.headline || local.profile.headline,
      tagline: s.tagline || local.profile.tagline,
      location: s.location || local.profile.location,
      about: s.bio || local.profile.about,
      email: s.email || local.profile.email,
      socials: local.profile.socials.map((soc) =>
        soc.label === "LinkedIn"
          ? { ...soc, href: s.linkedin_url || soc.href }
          : soc.label === "GitHub"
          ? { ...soc, href: s.github_url || soc.href }
          : soc,
      ) as typeof local.profile.socials,
    },
    skills: grouped.size
      ? Array.from(grouped.entries()).map(([category, v]) => ({
          category,
          level: v.level,
          bar: BAR[v.level] ?? 35,
          items: v.items,
        }))
      : local.skills,
    projects: projectRows.data?.length
      ? projectRows.data.map((r) => ({
          title: r.title as string,
          status: (r.status as string) || "In progress",
          tags: (r.tech as string[]) ?? [],
          description: (r.description as string) || (r.summary as string) || "",
          href: (r.demo_url as string) || "#",
          repo: (r.repo_url as string) || "",
        }))
      : local.projects,
    education: educationRows.data?.length
      ? educationRows.data.map((r) => ({
          school: r.institution as string,
          program: (r.programme as string) ?? "",
          detail: (r.note as string) ?? "",
          place: (r.location as string) ?? "",
          period: [r.start_year, r.end_year].filter(Boolean).join(" – ") || "Ongoing",
        }))
      : local.education,
    certifications: certRows.data?.length
      ? certRows.data.map((r) => ({
          title: r.title as string,
          issuer: (r.issuer as string) ?? "",
          year: r.year ? String(r.year) : "",
          file: (r.file_url as string) ?? "",
        }))
      : local.certifications,
  };
}
