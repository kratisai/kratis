import { describe, expect, it } from "vitest"

import { ASK_TEMPLATES, getTemplateById } from "@/lib/ask-templates"

describe("ask-templates registry", () => {
  it("contains all initial workflow templates", () => {
    const ids = ASK_TEMPLATES.map((t) => t.id)
    expect(ids).toEqual(["free-form", "plan-and-grill", "investigate-error", "pattern-audit"])
  })

  it("getTemplateById retrieves correct template", () => {
    const tpl = getTemplateById("plan-and-grill")
    expect(tpl).toBeDefined()
    expect(tpl?.title).toBe("Plan & Grill")
  })

  it("compiles Plan & Grill prompt correctly", () => {
    const tpl = getTemplateById("plan-and-grill")!
    const prompt = tpl.compilePrompt({
      description: "dark mode support",
      repo: "web-client",
    })
    expect(prompt).toContain("repository \"web-client\"")
    expect(prompt).toContain("dark mode support")
    expect(prompt).toContain("challenge my architectural assumptions")
  })

  it("compiles Investigate Error prompt with optional trace", () => {
    const tpl = getTemplateById("investigate-error")!
    const promptWithoutTrace = tpl.compilePrompt({
      repo: "api-service",
      symptoms: "500 Internal Server Error on checkout",
    })
    expect(promptWithoutTrace).toContain("repository \"api-service\"")
    expect(promptWithoutTrace).toContain("500 Internal Server Error on checkout")
    expect(promptWithoutTrace).not.toContain("Stack trace")

    const promptWithTrace = tpl.compilePrompt({
      repo: "api-service",
      symptoms: "NullPointerException",
      trace: "at com.example.OrderService.process(OrderService.java:42)",
    })
    expect(promptWithTrace).toContain("Stack trace / Error logs:")
    expect(promptWithTrace).toContain("OrderService.java:42")
  })

  it("compiles Architecture Audit prompt correctly", () => {
    const tpl = getTemplateById("pattern-audit")!
    const prompt = tpl.compilePrompt({
      repo: "core-monolith",
      targetPath: "src/billing",
    })
    expect(prompt).toContain("repository \"core-monolith\"")
    expect(prompt).toContain("path \"src/billing\"")
    expect(prompt).toContain("boundary violations")
  })
})
