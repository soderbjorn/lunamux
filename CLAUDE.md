# CLAUDE.md

## Documentation standards

Every non-third-party source file must be documented following these conventions:

### File-level documentation
- Each file must have a block comment at the very top (before the `package` declaration in Kotlin, or at line 1 in JS) describing the general purpose of the file and the class(es) it contains.

### Function/class/property documentation
- Every public class, function, and significant property must have a doc comment describing:
  - **What** it does
  - **Who calls it** and why (caller context)
  - **Parameters** — documented with `@param` tags
  - **Return values** — documented with `@return` tags
  - **Related symbols** — cross-referenced with `@see` tags where relevant

### Format
- **Kotlin files**: Use KDoc (`/** ... */`) following standard Kotlin documentation conventions.
- **JavaScript files**: Use JSDoc (`/** ... */`) with `@param`, `@returns`, and description blocks.

### Guidelines
- Do not duplicate or overwrite existing good documentation — augment it.
- Existing inline comments should be preserved; doc blocks are added on top of functions, not replacing inline context.
- Third-party / vendored code (e.g. `terminal-emulator`, `terminal-view`) is excluded from these requirements.
