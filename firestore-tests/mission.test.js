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

// Phase 5C: Mission Rooms (expiry, read-only grace period, checklist, announcement/outcome, saved notes).
const ROOM = 'MSN123';
const MIN = 60 * 1000;
const HOUR = 60 * MIN;
let env;

const asUser = (uid) => env.authenticatedContext(uid).firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-mission',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

// Seeds a mission room created `ageMs` ago with the given duration. Phases follow from the age:
// active until durationSeconds, read-only for the next 24 hours, then gone.
async function seedMission({ ageMs = 5 * MIN, durationSeconds = 1800, mission = {} } = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'rooms', ROOM), {
      roomCode: ROOM, creatorUid: 'owner', roomOpen: true,
      members: { owner: 'Owner', alice: 'Alice' }, temporary: false,
      timestamp: new Date(Date.now() - ageMs),
      mission: { title: 'Ship it', goal: 'Finish the demo', durationSeconds, ...mission },
    });
    await setDoc(doc(db, 'rooms', ROOM, 'messages', 'm1'), {
      uid: 'alice', username: 'Alice', messageText: 'hi', timestamp: new Date(),
    });
    await setDoc(doc(db, 'rooms', ROOM, 'tasks', 't1'), {
      text: 'Write tests', done: false, doneBy: '', createdBy: 'owner', order: 1,
    });
  });
}

const EXPIRED = { ageMs: 2 * HOUR }; // 30 min mission, ended 90 min ago, still in the grace period
const GONE = { ageMs: 3 * 24 * HOUR }; // long past the grace period

const msg = (uid, name) => ({ uid, username: name, messageText: 'hello', timestamp: serverTimestamp() });
const messages = (uid) => collection(asUser(uid), 'rooms', ROOM, 'messages');
const roomRef = (uid) => doc(asUser(uid), 'rooms', ROOM);

describe('creating a mission room', () => {
  const newRoom = (mission, over = {}) => ({
    roomCode: ROOM, creatorUid: 'owner', roomOpen: true, members: { owner: 'Owner' },
    temporary: false, timestamp: serverTimestamp(), mission, ...over,
  });
  const good = { title: 'Ship it', goal: 'Demo', durationSeconds: 1800 };

  it('accepts a well formed mission with an allowed duration', async () => {
    for (const durationSeconds of [1800, 21600, 86400]) {
      await env.clearFirestore();
      await assertSucceeds(setDoc(roomRef('owner'), newRoom({ ...good, durationSeconds })));
    }
  });

  it('rejects other durations, missing or oversized fields and extra fields', async () => {
    const db = roomRef('owner');
    await assertFails(setDoc(db, newRoom({ ...good, durationSeconds: 999999 })));
    await assertFails(setDoc(db, newRoom({ ...good, title: '' })));
    await assertFails(setDoc(db, newRoom({ ...good, title: 'x'.repeat(61) })));
    await assertFails(setDoc(db, newRoom({ ...good, goal: 'x'.repeat(201) })));
    await assertFails(setDoc(db, newRoom({ title: 'T', goal: 'G' })));
    await assertFails(setDoc(db, newRoom({ ...good, outcome: 'already done' })));
    await assertFails(setDoc(db, newRoom({ ...good, expiresAt: new Date(Date.now() + 99 * HOUR) })));
  });

  it('keeps plain rooms working without a mission', async () => {
    const plain = newRoom(undefined);
    delete plain.mission;
    await assertSucceeds(setDoc(roomRef('owner'), plain));
  });
});

describe('an active mission room', () => {
  it('works like a normal room: members chat, tick tasks, update presence and join', async () => {
    await seedMission();
    await assertSucceeds(addDoc(messages('alice'), msg('alice', 'Alice')));
    await assertSucceeds(updateDoc(doc(asUser('alice'), 'rooms', ROOM, 'tasks', 't1'), { done: true, doneBy: 'alice' }));
    await assertSucceeds(setDoc(doc(asUser('alice'), 'rooms', ROOM, 'presence', 'alice'), {
      username: 'Alice', isPresent: true, lastSeen: serverTimestamp(),
    }));
    await assertSucceeds(updateDoc(roomRef('bob'), { 'members.bob': 'Bob' }));
  });

  it('lets only the owner change the announcement and outcome, and nothing else in the mission', async () => {
    await seedMission();
    const base = { title: 'Ship it', goal: 'Finish the demo', durationSeconds: 1800 };
    await assertSucceeds(updateDoc(roomRef('owner'), { mission: { ...base, announcement: 'Stand-up at 10' } }));
    await assertFails(updateDoc(roomRef('alice'), { mission: { ...base, announcement: 'hijack' } }));
    await assertFails(updateDoc(roomRef('owner'), { mission: { ...base, durationSeconds: 86400 } }));
    await assertFails(updateDoc(roomRef('owner'), { mission: { ...base, title: 'Renamed' } }));
    await assertFails(updateDoc(roomRef('owner'), { mission: { ...base, announcement: 'x'.repeat(501) } }));
    await assertFails(updateDoc(roomRef('owner'), { mission: { ...base, outcome: 'x'.repeat(1001) } }));
    await assertFails(updateDoc(roomRef('owner'), { timestamp: new Date(), mission: base }));
  });
});

describe('the checklist', () => {
  const task = (over = {}) => ({
    text: 'Deploy', done: false, doneBy: '', createdBy: 'owner', order: 2, ...over,
  });
  const tasks = (uid) => collection(asUser(uid), 'rooms', ROOM, 'tasks');

  it('is created and deleted by the owner only', async () => {
    await seedMission();
    await assertSucceeds(addDoc(tasks('owner'), task()));
    await assertFails(addDoc(tasks('alice'), task({ createdBy: 'alice' })));
    await assertFails(deleteDoc(doc(asUser('alice'), 'rooms', ROOM, 'tasks', 't1')));
    await assertSucceeds(deleteDoc(doc(asUser('owner'), 'rooms', ROOM, 'tasks', 't1')));
  });

  it('rejects malformed tasks', async () => {
    await seedMission();
    await assertFails(addDoc(tasks('owner'), task({ text: '' })));
    await assertFails(addDoc(tasks('owner'), task({ text: 'x'.repeat(101) })));
    await assertFails(addDoc(tasks('owner'), task({ done: true })));
    await assertFails(addDoc(tasks('owner'), task({ extra: 1 })));
  });

  it('lets members flip only done and doneBy, and only as themselves', async () => {
    await seedMission();
    const ref = (uid) => doc(asUser(uid), 'rooms', ROOM, 'tasks', 't1');
    await assertFails(updateDoc(ref('alice'), { done: true, doneBy: 'owner' }));
    await assertFails(updateDoc(ref('alice'), { done: true }));
    await assertFails(updateDoc(ref('alice'), { text: 'changed' }));
    await assertFails(updateDoc(ref('stranger'), { done: true, doneBy: 'stranger' }));
    await assertSucceeds(updateDoc(ref('alice'), { done: true, doneBy: 'alice' }));
    await assertSucceeds(updateDoc(ref('alice'), { done: false, doneBy: '' }));
  });
});

describe('after the mission ends (read-only grace period)', () => {
  it('still lets members read the room and its history', async () => {
    await seedMission(EXPIRED);
    await assertSucceeds(getDoc(roomRef('alice')));
    await assertSucceeds(getDoc(doc(asUser('alice'), 'rooms', ROOM, 'messages', 'm1')));
    await assertSucceeds(getDoc(doc(asUser('alice'), 'rooms', ROOM, 'tasks', 't1')));
  });

  it('rejects new messages, edits, reactions, presence, tasks and joins', async () => {
    await seedMission(EXPIRED);
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice')));
    await assertFails(updateDoc(doc(asUser('alice'), 'rooms', ROOM, 'messages', 'm1'), { messageText: 'edit', edited: true }));
    await assertFails(updateDoc(doc(asUser('owner'), 'rooms', ROOM, 'messages', 'm1'), { reactions: { '👍': ['owner'] } }));
    await assertFails(setDoc(doc(asUser('alice'), 'rooms', ROOM, 'presence', 'alice'), {
      username: 'Alice', isPresent: true, lastSeen: serverTimestamp(),
    }));
    await assertFails(updateDoc(doc(asUser('alice'), 'rooms', ROOM, 'tasks', 't1'), { done: true, doneBy: 'alice' }));
    await assertFails(addDoc(collection(asUser('owner'), 'rooms', ROOM, 'tasks'), {
      text: 'late', done: false, doneBy: '', createdBy: 'owner', order: 9,
    }));
    await assertFails(updateDoc(roomRef('bob'), { 'members.bob': 'Bob' }));
  });

  it('still lets the owner write the final outcome but no longer the announcement', async () => {
    await seedMission(EXPIRED);
    const base = { title: 'Ship it', goal: 'Finish the demo', durationSeconds: 1800 };
    await assertSucceeds(updateDoc(roomRef('owner'), { mission: { ...base, outcome: 'Shipped on time' } }));
    await assertFails(updateDoc(roomRef('owner'), { mission: { ...base, announcement: 'too late' } }));
    await assertFails(updateDoc(roomRef('alice'), { mission: { ...base, outcome: 'faked' } }));
  });

  it('cannot be extended by changing the creation time or duration', async () => {
    await seedMission(EXPIRED);
    const base = { title: 'Ship it', goal: 'Finish the demo', durationSeconds: 86400 };
    await assertFails(updateDoc(roomRef('owner'), { mission: base }));
    await assertFails(updateDoc(roomRef('owner'), { timestamp: serverTimestamp() }));
  });
});

describe('after the grace period (room is gone)', () => {
  it('denies reading the room and every subcollection', async () => {
    await seedMission(GONE);
    await assertFails(getDoc(roomRef('alice')));
    await assertFails(getDoc(roomRef('owner')));
    await assertFails(getDoc(doc(asUser('alice'), 'rooms', ROOM, 'messages', 'm1')));
    await assertFails(getDoc(doc(asUser('alice'), 'rooms', ROOM, 'tasks', 't1')));
  });

  it('denies every write, including the outcome', async () => {
    await seedMission(GONE);
    const base = { title: 'Ship it', goal: 'Finish the demo', durationSeconds: 1800 };
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice')));
    await assertFails(updateDoc(roomRef('owner'), { mission: { ...base, outcome: 'late' } }));
  });
});

describe('saved notes', () => {
  const note = (over = {}) => ({
    roomTitle: 'Ship it', author: 'Alice', text: 'Remember this', source: 'message',
    savedAt: serverTimestamp(), ...over,
  });
  const mine = (uid) => collection(asUser(uid), 'users', uid, 'saved');

  it('are private to their owner and survive without the room', async () => {
    const ref = await addDoc(mine('alice'), note());
    await assertSucceeds(getDoc(doc(asUser('alice'), 'users', 'alice', 'saved', ref.id)));
    await assertFails(getDoc(doc(asUser('bob'), 'users', 'alice', 'saved', ref.id)));
    await assertFails(addDoc(collection(asUser('bob'), 'users', 'alice', 'saved'), note()));
    await assertFails(updateDoc(doc(asUser('alice'), 'users', 'alice', 'saved', ref.id), { text: 'edited' }));
    await assertFails(deleteDoc(doc(asUser('bob'), 'users', 'alice', 'saved', ref.id)));
    await assertSucceeds(deleteDoc(doc(asUser('alice'), 'users', 'alice', 'saved', ref.id)));
  });

  it('reject empty or oversized text, unknown sources, client times and extra fields', async () => {
    await assertFails(addDoc(mine('alice'), note({ text: '' })));
    await assertFails(addDoc(mine('alice'), note({ text: 'x'.repeat(2001) })));
    await assertFails(addDoc(mine('alice'), note({ source: 'whisper' })));
    await assertFails(addDoc(mine('alice'), note({ savedAt: new Date() })));
    await assertFails(addDoc(mine('alice'), note({ extra: true })));
  });
});
