"use client";
import CrudEditor from "@/components/admin/CrudEditor";

export default function Page() {
  return (
    <CrudEditor
      table="skills"
      title="skill"
      fields={[
        { key: "name", label: "Skill" },
        { key: "category", label: "Category", type: "select", options: ["AI", "Programming", "Design", "Business", "Language"] },
        { key: "level", label: "Honest level", type: "select", options: ["Learning", "Practising", "Comfortable"] },
        { key: "note", label: "Note", type: "textarea" },
        { key: "sort_order", label: "Sort order", type: "number" },
      ]}
    />
  );
}
