const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc, collection, setDoc, query, where, limit, getCountFromServer, Timestamp,
} = require('firebase/firestore');

// Phase 5F: the inbox counts unread messages with a count query. It is a list read, so the rules for
// listing messages decide who may do it. Also: read markers can be kept for any chat, not only DMs.
const ROOM = 'ABC123';
let env;
const asUser = (uid) => env.authenticatedContext(uid).firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-unread',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

async function seed() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'rooms', ROOM), {
      roomCode: ROOM, creatorUid: 'owner', roomOpen: true,
      members: { owner: 'Owner', alice: 'Alice' }, temporary: false, timestamp: new Date(),
    });
    for (let i = 0; i < 5; i++) {
      await setDoc(doc(db, 'rooms', ROOM, 'messages', 'm' + i), {
        uid: 'owner', username: 'Owner', messageText: 'hi ' + i, timestamp: new Date(Date.now() - (5 - i) * 60_000),
      });
    }
  });
}

const unread = (db, since, cap = 100) =>
  getCountFromServer(query(
    collection(db, 'rooms', ROOM, 'messages'),
    where('timestamp', '>', Timestamp.fromMillis(since)),
    limit(cap),
  ));

describe('counting unread messages', () => {
  it('lets a member count the messages after their read marker', async () => {
    await seed();
    const snap = await assertSucceeds(unread(asUser('alice'), Date.now() - 150_000));
    if (snap.data().count !== 2) throw new Error('expected 2, got ' + snap.data().count);
  });

  it('respects the cap', async () => {
    await seed();
    const snap = await assertSucceeds(unread(asUser('alice'), 0, 3));
    if (snap.data().count !== 3) throw new Error('expected 3, got ' + snap.data().count);
  });

  it('does not let a non-member or a signed-out user count', async () => {
    await seed();
    await assertFails(unread(asUser('stranger'), 0));
    await assertFails(unread(env.unauthenticatedContext().firestore(), 0));
  });
});

describe('read markers', () => {
  it('can be kept for a code room as well as for a DM, and stay private', async () => {
    const mine = doc(asUser('alice'), 'users', 'alice', 'chats', ROOM);
    await assertSucceeds(setDoc(mine, { lastReadAt: new Date() }, { merge: true }));
    await assertFails(setDoc(doc(asUser('bob'), 'users', 'alice', 'chats', ROOM), { lastReadAt: new Date() }));
  });
});
