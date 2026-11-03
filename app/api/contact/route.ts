import { NextResponse } from "next/server";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

type Payload = {
  name?: string;
  email?: string;
  subject?: string;
  message?: string;
  honeypot?: string;
};

export async function POST(req: Request) {
  let body: Payload;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  if (body.honeypot) {
    return NextResponse.json({ error: "Submission rejected." }, { status: 400 });
  }

  const name = (body.name || "").trim();
  const email = (body.email || "").trim();
  const subject = (body.subject || "").trim();
  const message = (body.message || "").trim();

  if (name.length < 2 || name.length > 80) {
    return NextResponse.json({ error: "Please enter your name (2 to 80 characters)." }, { status: 422 });
  }
  if (!EMAIL_RE.test(email) || email.length > 160) {
    return NextResponse.json({ error: "Please enter a valid email address." }, { status: 422 });
  }
  if (subject.length < 3 || subject.length > 140) {
    return NextResponse.json({ error: "Please add a subject (3 to 140 characters)." }, { status: 422 });
  }
  if (message.length < 10 || message.length > 2000) {
    return NextResponse.json({ error: "Please write a message between 10 and 2000 characters." }, { status: 422 });
  }

  const apiKey = process.env.RESEND_API_KEY;
  const to = process.env.CONTACT_TO_EMAIL;

  if (!apiKey || !to) {
    return NextResponse.json(
      {
        error:
          "Email delivery is not configured on this deployment yet. Set RESEND_API_KEY and CONTACT_TO_EMAIL, or email tekbhatta663@gmail.com directly.",
      },
      { status: 503 }
    );
  }

  try {
    const res = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        from: "Portfolio Contact <onboarding@resend.dev>",
        to: [to],
        reply_to: email,
        subject: `[Portfolio] ${subject}`,
        text: `From: ${name} <${email}>\n\n${message}`,
      }),
    });

    if (!res.ok) {
      return NextResponse.json(
        { error: "The email service rejected the message. Please email tekbhatta663@gmail.com directly." },
        { status: 502 }
      );
    }

    return NextResponse.json({ message: "Message sent. Tek will reply to your email address." });
  } catch {
    return NextResponse.json(
      { error: "Could not reach the email service. Please email tekbhatta663@gmail.com directly." },
      { status: 502 }
    );
  }
}
