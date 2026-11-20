#!/usr/bin/env node
// The README and the project site restate the install path, the release version and the language
// reference. This keeps the copies honest: it fails when an old path or coordinate survives, when a
// documented feature is missing from one of the two places that must describe it, or when the docs
// name a version other than the one in gradle.properties.
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const docsDir = path.join(root, "docs");
const pages = (await readdir(docsDir)).filter((name) => name.endsWith(".html")).sort();
const files = ["README.md", "RELEASING.md", ...pages.map((name) => `docs/${name}`)];

const failures = [];
const texts = new Map();
for (const file of files) {
    try {
        texts.set(file, await readFile(path.join(root, file), "utf8"));
    } catch (error) {
        failures.push(`${file}: cannot read (${error.message})`);
    }
}

// The version is written once, in gradle.properties. A doc that names a different one is telling
// people to install the previous release, which is what a version bump must not leave behind.
const versionSource = "gradle.properties";
let currentVersion;
try {
    currentVersion = /^whyVersion=(\S+)\s*$/m.exec(await readFile(path.join(root, versionSource), "utf8"))?.[1];
} catch (error) {
    failures.push(`${versionSource}: cannot read (${error.message})`);
}

const forbidden = [
    [/mysite\//, "old site folder 'mysite/'"],
    [/why:why-lang/, "old runtime coordinate 'why:why-lang'"],
    [/id\("why"\)/, 'old plugin id id("why")'],
];

for (const [file, text] of texts) {
    for (const [pattern, reason] of forbidden) {
        if (pattern.test(text)) failures.push(`${file}: ${reason}`);
    }
}

function requireIn(file, needles) {
    const text = texts.get(file);
    if (text === undefined) return;
    for (const needle of needles) {
        if (!text.includes(needle)) failures.push(`${file}: missing '${needle}'`);
    }
}

requireIn("README.md", [
    "io.github.slayyyyer.why",
    "mavenCentral()",
    "why new",
    "csrf.token",
    "response.json",
    "validate(",
]);
requireIn("docs/install.html", [
    "io.github.slayyyyer.why",
    "mavenCentral()",
    "why new",
]);
requireIn("docs/usage.html", ["csrf.token", "response.json", "validate("]);

if (currentVersion !== undefined) {
    requireIn("README.md", [`io.github.slayyyyer:why-lang:${currentVersion}`]);
    requireIn("docs/install.html", [`io.github.slayyyyer:why-lang:${currentVersion}`]);
}

// Every version-shaped number in these files must be the current release, a bind address or a
// declared third-party pin. Prose like "apply 0.1.0" is caught the same way a snippet is. The
// pattern runs over dotted numbers only, so `why-lang-0.1.0.pom` yields `0.1.0`, not the extension.
const thirdPartyVersions = new Set(["8.14.3"]); // the Gradle wrapper the repository pins
const isAddress = (value) =>
    /^\d{1,3}(?:\.\d{1,3}){3}$/.test(value) && value.split(".").every((part) => Number(part) <= 255);

if (currentVersion !== undefined) {
    for (const [file, text] of texts) {
        for (const match of text.matchAll(/\b\d+(?:\.\d+){2,}(?:-[0-9A-Za-z]+)*\b/g)) {
            const found = match[0];
            if (found === currentVersion || isAddress(found) || thirdPartyVersions.has(found)) continue;
            const line = text.slice(0, match.index).split("\n").length;
            failures.push(
                `${file}:${line}: names version '${found}' but ${versionSource} says '${currentVersion}'`);
        }
    }
}

if (failures.length > 0) {
    console.error("docs are out of sync:");
    for (const failure of failures) console.error(`  ${failure}`);
    process.exit(1);
}
console.log(`docs check: ${files.length} files agree on ${currentVersion}`);
