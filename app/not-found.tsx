import Link from "next/link";

export default function NotFound() {
  return (
    <main className="wrap" style={{ minHeight: "78vh", display: "grid", placeItems: "center", textAlign: "center" }}>
      <div>
        <p className="section-kicker">404</p>
        <h1 style={{ fontSize: "clamp(30px,8vw,58px)", margin: "10px 0" }}>
          This page went <span className="grad">missing</span>
        </h1>
        <p style={{ color: "var(--muted)", maxWidth: 420, margin: "0 auto 26px" }}>
          The link you followed does not exist here. Nothing was invented to fill the gap.
        </p>
        <Link className="btn btn-primary" href="/">Back to the portfolio</Link>
      </div>
    </main>
  );
}
