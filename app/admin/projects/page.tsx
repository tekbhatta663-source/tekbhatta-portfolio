"use client";
import CrudEditor from "@/components/admin/CrudEditor";

export default function Page() {
  return (
    <CrudEditor
      table="projects"
      title="project"
      emptyHint="Add your first project. Until you do, the site shows the launch placeholder honestly."
      fields={[
        { key: "title", label: "Title" },
        { key: "summary", label: "One-line summary" },
        { key: "description", label: "Description", type: "textarea" },
        { key: "tech", label: "Tech tags (comma separated)", type: "tags", placeholder: "Next.js, TypeScript" },
        { key: "status", label: "Status", type: "select", options: ["In Progress", "Live", "Planned"] },
        { key: "repo_url", label: "Repository URL", type: "url" },
        { key: "demo_url", label: "Live demo URL", type: "url" },
        { key: "image_url", label: "Screenshot", type: "file", bucket: "media" },
        { key: "sort_order", label: "Sort order", type: "number" },
      ]}
    />
  );
}
