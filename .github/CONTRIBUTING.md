# Contributing to RavenTag

Thank you for considering contributing to RavenTag! This document provides guidelines for contributing to the project.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)
- [Pull Request Guidelines](#pull-request-guidelines)
- [Coding Standards](#coding-standards)

---

## Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Keep discussions on-topic and professional

---

## How Can I Contribute?

### ⚠️ Important Notice

**This is a maintainer-led project.** All contributions are reviewed and merged at the discretion of the maintainer (@ALENOC). 

**Ways to contribute:**
1. 🐛 Report bugs via Issues
2. 💡 Suggest features via Issues  
3. 🔧 Submit Pull Requests for bug fixes or improvements
4. 📖 Improve documentation
5. 🌍 Help with translations

---

## Reporting Bugs

### Before Submitting a Bug Report

- [ ] Check the [existing Issues](https://github.com/ALENOC/RavenTag/issues) to see if it's already reported
- [ ] Update to the latest version (v1.0.3 or newer)
- [ ] Gather information about your environment (Android version, device, app variant)

### How to Submit a Bug Report

Use the [Bug Report Template](.github/ISSUE_TEMPLATE/bug_report.md) and provide:

- Clear description of the issue
- Steps to reproduce
- Expected vs actual behavior
- Screenshots if applicable
- Environment details

---

## Suggesting Features

### Before Submitting a Feature Request

- [ ] Check existing Issues for similar requests
- [ ] Consider if this aligns with RavenTag's core mission (decentralized, trustless authentication)
- [ ] Think about technical feasibility

### How to Submit a Feature Request

Use the [Feature Request Template](.github/ISSUE_TEMPLATE/feature_request.md) and include:

- Problem description
- Proposed solution
- Use cases
- Technical considerations

**Note:** Feature acceptance is at the maintainer's discretion. Not all requests will be implemented.

---

## Pull Request Guidelines

### Before Submitting a PR

- [ ] Ensure your changes don't break existing functionality
- [ ] Test on both Verify and Brand app variants (if applicable)
- [ ] Update documentation if needed
- [ ] Follow coding standards (see below)

### PR Process

1. **Fork** the repository
2. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes**
4. **Test thoroughly**
5. **Commit** with clear messages
6. **Push** to your fork
7. **Open a Pull Request**

### PR Template

When opening a PR, please include:

- **Description**: What does this PR do?
- **Motivation**: Why is this needed?
- **Testing**: How did you test this?
- **Screenshots**: If UI changes (optional)

### Review Process

- The maintainer will review your PR
- Feedback may be provided for improvements
- Not all PRs will be accepted
- Be patient - review times vary

---

## Coding Standards

### Kotlin (Android)

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Keep functions small and focused
- Add KDoc comments for public APIs
- Handle errors gracefully

### TypeScript (Frontend)

- Follow [TypeScript coding conventions](https://google.github.io/styleguide/tsguide.html)
- Use strict mode
- Prefer functional programming patterns
- Add JSDoc comments for public functions

### General

- No trailing whitespace
- Use 2 spaces for indentation
- Max line length: 100 characters
- Use meaningful commit messages

---

## Security

**For security issues, DO NOT create a public Issue.**

Contact the maintainer directly:
- Email: alessandro.nocentini@yahoo.it
- Use the [Security Issue Template](.github/ISSUE_TEMPLATE/security_issue.md) for guidance

---

## Questions?

If you have questions about contributing:
1. Check the [Documentation](https://github.com/ALENOC/RavenTag/tree/main/docs)
2. Read existing Issues for similar questions
3. Open a general Issue asking your question

---

## License

By contributing, you agree that your contributions will be licensed under the **RavenTag Source License (RTSL-1.0)**.
