# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

*Your notes:*
The app is a public feed (`InkFeed`) where users can view posts, search them, and leave comments without an account. 
An unauthenticated stranger controls three entry points (inputs) that reach the backend:
1. The search query parameter (`q`) in `GET /api/posts/search?q=...`
2. The comment body and author fields in `POST /api/posts/{id}/comments`
3. The post ID path variable in the comment endpoint.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

I typed the following payload into the search box on the frontend (`http://localhost:3000`):

```text
x' UNION SELECT 1, email, password FROM users --