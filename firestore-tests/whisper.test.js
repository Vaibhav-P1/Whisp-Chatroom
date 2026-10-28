const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, collection, setDoc, updateDoc, deleteDoc, addDoc,
  serverTimestamp,
} = require('firebase/firestore');

// Phase 5D: whisper messages (hide themselves after being opened) and best-effort cleanup.
const ROOM = 'WHS123';
const SECOND = 1000;
const MIN = 60 * SECOND;
const HOUR = 60 * MIN;
let env;

const asUser = (uid) => env.authenticatedContext(uid).firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-whisper',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

async function seed(messages = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'rooms', ROOM), {
      roomCode: ROOM, creatorUid: 'owner', roomOpen: true,
      members: { owner: 'Owner', alice: 'Alice', bob: 'Bob' }, temporary: false, timestamp: new Date(),
    });
    for (const [id, data] of Object.entries(messages)) {
      await setDoc(doc(db, 'rooms', ROOM, 'messages', id), data);
    }
  });
}

const whisper = (over = {}) => ({
  uid: 'alice', username: 'Alice', messageText: 'secret', timestamp: new Date(), ttlSeconds: 60, ...over,
});
const plain = (over = {}) => ({
  uid: 'alice', username: 'Alice', messageText: 'hello', timestamp: new Date(), ...over,
});
const ref = (uid, id) => doc(asUser(uid), 'rooms', ROOM, 'messages', id);
const messages = (uid) => collection(asUser(uid), 'rooms', ROOM, 'messages');
const send = (over = {}) => ({
  uid: 'alice', username: 'Alice', messageText: 'secret', timestamp: serverTimestamp(), ttlSeconds: 60, ...over,
});

describe('sending a whisper', () => {
  it('accepts the three allowed lifetimes', async () => {
    await seed();
    for (const ttlSeconds of [10, 60, 3600]) {
      await assertSucceeds(addDoc(messages('alice'), send({ ttlSeconds })));
    }
  });

  it('rejects other lifetimes, a client-written view time, a quote and a non-member', async () => {
    await seed();
    await assertFails(addDoc(messages('alice'), send({ ttlSeconds: 5 })));
    await assertFails(addDoc(messages('alice'), send({ ttlSeconds: 999999 })));
    await assertFails(addDoc(messages('alice'), send({ firstViewedAt: serverTimestamp() })));
    await assertFails(addDoc(messages('alice'), send({ replyTo: { id: 'x', username: 'Bob', text: 'q' } })));
    await assertFails(addDoc(messages('stranger'), send({ uid: 'stranger', username: 'S' })));
  });
});

describe('opening a whisper', () => {
  it('lets a member other than the author start the timer exactly once', async () => {
    await seed({ w1: whisper() });
    await assertSucceeds(updateDoc(ref('bob', 'w1'), { firstViewedAt: serverTimestamp() }));
    await assertFails(updateDoc(ref('owner', 'w1'), { firstViewedAt: serverTimestamp() }));
  });

  it('never lets the author start their own timer', async () => {
    await seed({ w1: whisper() });
    await assertFails(updateDoc(ref('alice', 'w1'), { firstViewedAt: serverTimestamp() }));
  });

  it('rejects a client-chosen time, extra fields, strangers and normal messages', async () => {
    await seed({ w1: whisper(), p1: plain() });
    await assertFails(updateDoc(ref('bob', 'w1'), { firstViewedAt: new Date() }));
    await assertFails(updateDoc(ref('bob', 'w1'), { firstViewedAt: serverTimestamp(), ttlSeconds: 3600 }));
    await assertFails(updateDoc(ref('stranger', 'w1'), { firstViewedAt: serverTimestamp() }));
    await assertFails(updateDoc(ref('bob', 'p1'), { firstViewedAt: serverTimestamp() }));
  });

  it('cannot be reset or moved once started', async () => {
    await seed({ w1: whisper({ firstViewedAt: new Date() }) });
    await assertFails(updateDoc(ref('bob', 'w1'), { firstViewedAt: serverTimestamp() }));
    await assertFails(updateDoc(ref('owner', 'w1'), { firstViewedAt: serverTimestamp() }));
  });

  it('stops working after the 24 hour cap', async () => {
    await seed({ old: whisper({ timestamp: new Date(Date.now() - 25 * HOUR) }) });
    await assertFails(updateDoc(ref('bob', 'old'), { firstViewedAt: serverTimestamp() }));
  });
});

describe('whisper restrictions', () => {
  it('cannot be edited, but the author can still delete it', async () => {
    await seed({ w1: whisper() });
    await assertFails(updateDoc(ref('alice', 'w1'), { messageText: 'changed', edited: true }));
    await assertSucceeds(updateDoc(ref('alice', 'w1'), { deleted: true, messageText: '' }));
  });

  it('cannot be quoted by a reply, while normal messages still can', async () => {
    await seed({ w1: whisper(), p1: plain() });
    const reply = (id) => ({
      uid: 'bob', username: 'Bob', messageText: 'answer', timestamp: serverTimestamp(),
      replyTo: { id, username: 'Alice', text: 'quote' },
    });
    await assertFails(addDoc(messages('bob'), reply('w1')));
    await assertSucceeds(addDoc(messages('bob'), reply('p1')));
  });
});

describe('cleanup of hidden whispers', () => {
  it('lets a member delete a whisper once its timer ran out', async () => {
    await seed({ gone: whisper({ ttlSeconds: 10, firstViewedAt: new Date(Date.now() - MIN) }) });
    await assertSucceeds(deleteDoc(ref('bob', 'gone')));
  });

  it('lets a member delete a never-opened whisper after the 24 hour cap', async () => {
    await seed({ stale: whisper({ timestamp: new Date(Date.now() - 25 * HOUR) }) });
    await assertSucceeds(deleteDoc(ref('bob', 'stale')));
  });

  it('refuses to delete a whisper that is still visible', async () => {
    await seed({
      fresh: whisper(),
      running: whisper({ ttlSeconds: 3600, firstViewedAt: new Date() }),
    });
    await assertFails(deleteDoc(ref('bob', 'fresh')));
    await assertFails(deleteDoc(ref('bob', 'running')));
  });

  it('refuses members and strangers deleting normal messages, but not the owner', async () => {
    await seed({ p1: plain({ timestamp: new Date(Date.now() - 48 * HOUR) }) });
    await assertFails(deleteDoc(ref('bob', 'p1')));
    await assertFails(deleteDoc(ref('stranger', 'p1')));
    await assertSucceeds(deleteDoc(ref('owner', 'p1')));
  });

  it('keeps the whisper readable by members until it is deleted (hiding is logical)', async () => {
    await seed({ gone: whisper({ ttlSeconds: 10, firstViewedAt: new Date(Date.now() - MIN) }) });
    await assertSucceeds(getDoc(ref('bob', 'gone')));
  });
});
