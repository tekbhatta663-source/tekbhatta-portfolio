export const profile = {
  name: "TEKBHATTA",
  fullName: "Tek Bhatta",
  headline: "AI Learner • Aspiring Developer • Student",
  location: "Dhangadhi, Nepal",
  tagline:
    "Learning AI, LLMs, agentic AI, prompt engineering, and web and app development — one honest step at a time.",
  about:
    "I am a BBS first-year student at Aishwarya Multiple Campus in Dhangadhi, Nepal. My days are split between university coursework and teaching myself how modern software and AI systems actually work. I am not a finished developer and I do not pretend to be one. What I can show is consistent, documented progress: I build small things, break them, fix them, and write about what I learned. This site exists to keep that record honest and in public.",
  email: "tekbhatta663@gmail.com",
  socials: [
    { label: "LinkedIn", href: "https://www.linkedin.com/in/tek-bhatta-1433a3356", icon: "in" },
    { label: "GitHub", href: "https://github.com/tekbhatta663-source", icon: "gh" },
    { label: "Email", href: "mailto:tekbhatta663@gmail.com", icon: "@" },
  ],
};

export const journey = [
  {
    title: "Artificial Intelligence",
    body: "Started with prompt engineering, moved into how LLMs actually behave, then into agentic workflows. Currently reading about retrieval, tool use and evaluation.",
    status: "In progress",
  },
  {
    title: "Programming",
    body: "Python fundamentals and Kotlin for Android. Learning by rebuilding things from scratch rather than copying tutorials to the end.",
    status: "In progress",
  },
  {
    title: "Technology",
    body: "Git and GitHub workflow, the terminal, deploying to the web, and understanding what happens between a request and a rendered page.",
    status: "In progress",
  },
];

// Self-assessed, editable. No invented percentages: each level maps to a bar width.
// Edit freely in this file; nothing here is a verified credential.
export const skills = [
  {
    category: "AI & LLMs",
    level: "Learning",
    bar: 35,
    items: ["Prompt engineering", "LLM basics", "Agentic workflows", "AI content creation"],
  },
  {
    category: "Prompt Engineering",
    level: "Practising",
    bar: 45,
    items: ["Structured prompting", "Iterative refinement", "Multilingual prompts"],
  },
  {
    category: "Web Development",
    level: "Learning",
    bar: 30,
    items: ["HTML & CSS", "Next.js basics", "Deployment"],
  },
  {
    category: "App Development",
    level: "Learning",
    bar: 25,
    items: ["Kotlin", "Android Studio", "Gradle builds"],
  },
];

export const projects = [
  {
    title: "Portfolio Website",
    status: "In progress",
    tags: ["AI", "Web Dev"],
    description:
      "This site. Built to hold the learning record, with a multilingual assistant that answers only from a verified knowledge base.",
    href: "#",
    repo: "",
  },
  {
    title: "Android Cache Cleaner",
    status: "Learning project",
    tags: ["Kotlin", "Android"],
    description:
      "A Kotlin app that analyses on-device app cache and suggests what is safe to clear. Started as a way to understand Android storage APIs.",
    href: "",
    repo: "https://github.com/tekbhatta663-source/android-cache-cleaner",
  },
];

export const education = [
  {
    school: "Aishwarya Multiple Campus (AMC)",
    program: "Bachelor of Business Studies (BBS)",
    detail: "First year, Tribhuvan University",
    place: "Dhangadhi, Nepal",
    period: "Ongoing",
  },
];

export const certifications: { title: string; issuer: string; year: string; file: string }[] = [];
