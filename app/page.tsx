import Nav from "@/components/Nav";
import Reveal from "@/components/Reveal";
import ChatWidget from "@/components/ChatWidget";
import ContactForm from "@/components/ContactForm";
import ChatTeaser from "@/components/ChatTeaser";
import { profile, journey, skills, projects, education, certifications } from "@/data/content";

function SectionHead({ kicker, title, note }: { kicker: string; title: string; note?: string }) {
  return (
    <div className="section-head">
      <div>
        <div className="section-kicker">{kicker}</div>
        <h2>{title}</h2>
        {note && <p className="section-note">{note}</p>}
      </div>
    </div>
  );
}

export default function Home() {
  return (
    <>
      <Nav />

      <main id="top">
        {/* HERO */}
        <div className="wrap hero">
          <div className="hero-grid">
            <div>
              <span className="eyebrow">
                <span className="dot" aria-hidden="true" /> Learning in public · Nepal
              </span>

              <h1 className="hero-title">
                Hi, I&apos;m <span className="grad">{profile.name}</span>
              </h1>

              <p className="hero-sub" style={{ fontWeight: 600, color: "var(--text)", marginBottom: 10 }}>
                {profile.headline}
              </p>
              <p className="hero-sub">
                Based in {profile.location} — {profile.tagline}
              </p>

              <div className="cta-row">
                <a className="btn btn-primary" href="#projects">View Projects</a>
                <a className="btn btn-ghost" href="#contact">Contact Me</a>
              </div>

              <ChatTeaser />
            </div>

            <div style={{ justifySelf: "center" }} aria-hidden="true">
              <div className="avatar-ring">
                <div className="avatar-inner">TB</div>
              </div>
            </div>
          </div>
        </div>

        {/* ABOUT */}
        <section id="about">
          <div className="wrap">
            <Reveal>
              <SectionHead kicker="01 — About" title="About Me" />
              <div className="card">
                <p style={{ fontSize: 15.5 }}>{profile.about}</p>
              </div>
            </Reveal>
          </div>
        </section>

        {/* JOURNEY */}
        <section id="journey">
          <div className="wrap">
            <Reveal>
              <SectionHead
                kicker="02 — Journey"
                title="Learning Journey"
                note="What I am actually working through right now, not what I have finished."
              />
            </Reveal>
            <div className="grid-3">
              {journey.map((j, i) => (
                <Reveal key={j.title} delay={i * 90}>
                  <div className="card" style={{ height: "100%" }}>
                    <div className="tag-row" style={{ marginTop: 0, marginBottom: 12 }}>
                      <span className="tag hot">{j.status}</span>
                    </div>
                    <h3>{j.title}</h3>
                    <p>{j.body}</p>
                  </div>
                </Reveal>
              ))}
            </div>
          </div>
        </section>

        {/* SKILLS */}
        <section id="skills">
          <div className="wrap">
            <Reveal>
              <SectionHead
                kicker="03 — Skills"
                title="Skills"
                note="Self-assessed levels, deliberately modest. Nothing here is claimed as expertise."
              />
            </Reveal>
            <Reveal delay={80}>
              <div className="card">
                {skills.map((s) => (
                  <div className="skill-row" key={s.category}>
                    <div className="skill-top">
                      <span className="skill-name">{s.category}</span>
                      <span className="skill-level">{s.level}</span>
                    </div>
                    <div
                      className="bar"
                      role="progressbar"
                      aria-label={`${s.category} self-assessed level`}
                      aria-valuenow={s.bar}
                      aria-valuemin={0}
                      aria-valuemax={100}
                    >
                      <span style={{ ["--w" as string]: `${s.bar}%` }} />
                    </div>
                    <div className="skill-items">
                      {s.items.map((it) => (
                        <span className="tag" key={it}>{it}</span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </Reveal>
          </div>
        </section>

        {/* PROJECTS */}
        <section id="projects">
          <div className="wrap">
            <Reveal>
              <SectionHead kicker="04 — Work" title="Projects" note="Only things that exist. In-progress work is labelled as such." />
            </Reveal>
            <div className="grid-2">
              {projects.map((p, i) => (
                <Reveal key={p.title} delay={i * 90}>
                  <div className="card" style={{ height: "100%", display: "flex", flexDirection: "column" }}>
                    <h3>{p.title}</h3>
                    <p style={{ flex: 1 }}>{p.description}</p>
                    <div className="tag-row">
                      <span className="tag hot">{p.status}</span>
                      {p.tags.map((t) => (
                        <span className="tag" key={t}>{t}</span>
                      ))}
                    </div>
                    <div className="cta-row" style={{ marginTop: 16 }}>
                      {p.href && p.href !== "#" && (
                        <a className="btn btn-ghost" href={p.href} target="_blank" rel="noopener noreferrer">Live Demo</a>
                      )}
                      {p.repo ? (
                        <a className="btn btn-ghost" href={p.repo} target="_blank" rel="noopener noreferrer">Repository</a>
                      ) : (
                        <span className="hint">Repo link coming soon</span>
                      )}
                    </div>
                  </div>
                </Reveal>
              ))}
            </div>
          </div>
        </section>

        {/* EDUCATION */}
        <section id="education">
          <div className="wrap">
            <Reveal>
              <SectionHead kicker="05 — Study" title="Education" />
            </Reveal>
            <div className="grid-2">
              {education.map((e) => (
                <Reveal key={e.school}>
                  <div className="card">
                    <h3>{e.program}</h3>
                    <p style={{ color: "var(--text)", fontWeight: 600 }}>{e.school}</p>
                    <p>{e.detail} · {e.place}</p>
                    <div className="tag-row">
                      <span className="tag">{e.period}</span>
                    </div>
                  </div>
                </Reveal>
              ))}
            </div>
          </div>
        </section>

        {/* CERTIFICATIONS */}
        <section id="certifications">
          <div className="wrap">
            <Reveal>
              <SectionHead
                kicker="06 — Proof"
                title="Certifications"
                note="Shown here only when a certificate actually exists and is uploaded."
              />
              <div className="card">
                {certifications.length === 0 ? (
                  <p>
                    Nothing uploaded yet. When a course or certificate is completed, the document will appear
                    here with its issuer and date — no placeholder certificates.
                  </p>
                ) : (
                  <div className="grid-2">
                    {certifications.map((c) => (
                      <div key={c.title} className="card">
                        <h3>{c.title}</h3>
                        <p>{c.issuer} · {c.year}</p>
                        <a className="btn btn-ghost" href={c.file} target="_blank" rel="noopener noreferrer">View certificate</a>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </Reveal>
          </div>
        </section>

        {/* CONTACT */}
        <section id="contact">
          <div className="wrap">
            <Reveal>
              <SectionHead
                kicker="07 — Contact"
                title="Get in touch"
                note="For opportunities, collaborations or questions about anything on this site."
              />
            </Reveal>
            <div className="grid-2">
              <Reveal>
                <div className="card" style={{ height: "100%" }}>
                  <h3>Direct</h3>
                  <p>Email is the fastest route. I read everything and reply when I can.</p>
                  <div className="cta-row">
                    <a className="btn btn-primary" href={`mailto:${profile.email}`}>{profile.email}</a>
                  </div>
                  <p style={{ marginTop: 20, fontSize: 13 }}>
                    Based in {profile.location}. Open to learning opportunities, internships and remote work.
                  </p>
                </div>
              </Reveal>
              <Reveal delay={90}>
                <div className="card">
                  <ContactForm />
                </div>
              </Reveal>
            </div>
          </div>
        </section>
      </main>

      <footer>
        <div className="wrap foot-row">
          <div>
            <div style={{ fontWeight: 700, letterSpacing: "0.14em", fontSize: 12.5, color: "var(--text)" }}>TEKBHATTA</div>
            <div style={{ marginTop: 6 }}>© 2026 Tek Bhatta · Dhangadhi, Nepal</div>
          </div>
          <div className="socials">
            {profile.socials.map((s) => (
              <a
                key={s.label}
                className="social"
                href={s.href}
                target={s.href.startsWith("mailto:") ? undefined : "_blank"}
                rel={s.href.startsWith("mailto:") ? undefined : "noopener noreferrer"}
                aria-label={s.label}
                title={s.label}
              >
                {s.icon}
              </a>
            ))}
          </div>
        </div>
      </footer>

      <ChatWidget />
    </>
  );
}
