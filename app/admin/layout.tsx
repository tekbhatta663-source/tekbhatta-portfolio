"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { getSupabase, isConfigured } from "@/lib/supabase";

const LINKS = [
  ["", "Overview"],
  ["projects", "Projects"],
  ["skills", "Skills"],
  ["education", "Education"],
  ["certifications", "Certifications"],
  ["media", "Media"],
  ["messages", "Messages"],
  ["knowledge", "Tek AI"],
  ["settings", "Settings"],
] as const;

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname() || "/admin";
  const router = useRouter();
  const [state, setState] = useState<"checking" | "ok" | "noauth" | "unconfigured">("checking");
  const isLogin = path.startsWith("/admin/login");

  useEffect(() => {
    if (isLogin) return;
    if (!isConfigured) return setState("unconfigured");
    const sb = getSupabase();
    if (!sb) return setState("unconfigured");
    sb.auth.getSession().then(({ data }) => {
      if (data.session) setState("ok");
      else {
        setState("noauth");
        router.replace("/admin/login");
      }
    });
  }, [isLogin, router]);

  if (isLogin) return <div className="admin-shell">{children}</div>;

  if (state === "unconfigured") {
    return (
      <div className="admin-shell">
        <div className="admin-panel">
          <h1 className="admin-h1">Admin needs Supabase</h1>
          <p className="muted">
            Add <code>NEXT_PUBLIC_SUPABASE_URL</code> and <code>NEXT_PUBLIC_SUPABASE_ANON_KEY</code> to
            your environment, then run <code>supabase/schema.sql</code> in the Supabase SQL editor.
            The setup steps are in the README.
          </p>
          <Link className="btn btn-ghost" href="/">Back to site</Link>
        </div>
      </div>
    );
  }

  if (state !== "ok") {
    return (
      <div className="admin-shell">
        <p className="muted">Checking your session…</p>
      </div>
    );
  }

  return (
    <div className="admin-shell">
      <header className="admin-bar">
        <Link href="/admin" className="admin-brand">TEK<span>ADMIN</span></Link>
        <nav className="admin-nav">
          {LINKS.map(([slug, label]) => {
            const href = slug ? `/admin/${slug}` : "/admin";
            const active = path === href;
            return (
              <Link key={href} href={href} className={active ? "admin-link is-active" : "admin-link"}>
                {label}
              </Link>
            );
          })}
        </nav>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={async () => {
            await getSupabase()?.auth.signOut();
            router.replace("/admin/login");
          }}
        >
          Sign out
        </button>
      </header>
      <main className="admin-main">{children}</main>
    </div>
  );
}
