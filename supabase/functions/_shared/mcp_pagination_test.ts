import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import { pageBounds } from "./mcp_pagination.ts";

Deno.test("MCP task pagination uses inclusive stable ranges", () => {
  assertEquals(pageBounds(0, 50), [0, 49]);
  assertEquals(pageBounds(50, 50), [50, 99]);
  assertEquals(pageBounds(200, 25), [200, 224]);
});

Deno.test("MCP task pagination rejects invalid bounds", () => {
  assertThrows(() => pageBounds(-1, 50), RangeError);
  assertThrows(() => pageBounds(0, 101), RangeError);
});
