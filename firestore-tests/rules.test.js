const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, addDoc,
  serverTimestamp, query, limit,
} = require('firebase/firestore');

const ROOM = 'ABC123';
let env;

const asUser = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

// Seeds a room owned by "owner" with "alice" already a member.
async function seedRoom({ roomOpen = true } = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'rooms', ROOM), {
      roomCode: ROOM,
      creatorUid: 'owner',
      roomOpen,
      members: { owner: 'Owner', alice: 'Alice' },
      temporary: false,
      timestamp: new Date(),
    });
    await setDoc(doc(db, 'rooms', ROOM, 'messages', 'm1'), {
      uid: 'alice', username: 'Alice', messageText: 'hi', timestamp: new Date(),
    });
  });
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

describe('users', () => {
  const profile = { firstName: 'Ann', lastName: 'Lee', email: 'a@b.com' };

  it('lets a user write and read only their own profile', async () => {
    await assertSucceeds(setDoc(doc(asUser('u1'), 'users', 'u1'), profile));
    await assertSucceeds(getDoc(doc(asUser('u1'), 'users', 'u1')));
    await assertFails(getDoc(doc(asUser('u2'), 'users', 'u1')));
    await assertFails(setDoc(doc(asUser('u2'), 'users', 'u1'), profile));
  });

  it('rejects unauthenticated access and unexpected fields', async () => {
    await assertFails(setDoc(doc(anon(), 'users', 'u1'), profile));
    await assertFails(setDoc(doc(asUser('u1'), 'users', 'u1'), { ...profile, admin: true }));
  });
});

describe('rooms', () => {
  const newRoom = (uid, over = {}) => ({
    roomCode: ROOM, creatorUid: uid, roomOpen: true, members: { [uid]: 'Owner' },
    temporary: false, timestamp: serverTimestamp(), ...over,
  });

  it('lets a signed-in user create a room they own', async () => {
    await assertSucceeds(setDoc(doc(asUser('owner'), 'rooms', ROOM), newRoom('owner')));
  });

  it('rejects creating a room for someone else, with a bad code or extra fields', async () => {
    const db = asUser('owner');
    await assertFails(setDoc(doc(db, 'rooms', ROOM), newRoom('someone-else')));
    await assertFails(setDoc(doc(db, 'rooms', 'abc'), newRoom('owner', { roomCode: 'abc' })));
    await assertFails(setDoc(doc(db, 'rooms', ROOM), newRoom('owner', { extra: 1 })));
    await assertFails(setDoc(doc(anon(), 'rooms', ROOM), newRoom('owner')));
  });

  it('cannot overwrite an existing room (code collision)', async () => {
    await seedRoom();
    await assertFails(setDoc(doc(asUser('mallory'), 'rooms', ROOM), newRoom('mallory')));
  });

  it('allows get by code but never listing rooms', async () => {
    await seedRoom();
    await assertSucceeds(getDoc(doc(asUser('stranger'), 'rooms', ROOM)));
    await assertFails(getDoc(doc(anon(), 'rooms', ROOM)));
    await assertFails(getDocs(collection(asUser('stranger'), 'rooms')));
  });

  it('lets a user join an open room only as themselves', async () => {
    await seedRoom();
    await assertSucceeds(updateDoc(doc(asUser('bob'), 'rooms', ROOM), { 'members.bob': 'Bob' }));
  });

  it('rejects joining as another uid, changing others, or touching other fields', async () => {
    await seedRoom();
    const ref = doc(asUser('bob'), 'rooms', ROOM);
    await assertFails(updateDoc(ref, { 'members.carol': 'Carol' }));
    await assertFails(updateDoc(ref, { 'members.alice': 'Hacked' }));
    await assertFails(updateDoc(ref, { creatorUid: 'bob' }));
    await assertFails(updateDoc(ref, { 'members.bob': 'Bob', roomOpen: false }));
  });

  it('rejects joining a closed room', async () => {
    await seedRoom({ roomOpen: false });
    await assertFails(updateDoc(doc(asUser('bob'), 'rooms', ROOM), { 'members.bob': 'Bob' }));
  });

  it('lets only the owner close or delete the room', async () => {
    await seedRoom();
    await assertFails(updateDoc(doc(asUser('alice'), 'rooms', ROOM), { roomOpen: false }));
    await assertFails(deleteDoc(doc(asUser('alice'), 'rooms', ROOM)));
    await assertSucceeds(updateDoc(doc(asUser('owner'), 'rooms', ROOM), { roomOpen: false }));
    await assertSucceeds(deleteDoc(doc(asUser('owner'), 'rooms', ROOM)));
  });
});

describe('messages', () => {
  const msg = (uid, name, over = {}) => ({
    uid, username: name, messageText: 'hello', timestamp: serverTimestamp(), ...over,
  });
  const messages = (uid) => collection(asUser(uid), 'rooms', ROOM, 'messages');

  it('lets members read and send messages', async () => {
    await seedRoom();
    await assertSucceeds(getDocs(query(messages('alice'), limit(50))));
    await assertSucceeds(addDoc(messages('alice'), msg('alice', 'Alice')));
  });

  it('blocks non-members from reading or sending', async () => {
    await seedRoom();
    await assertFails(getDocs(query(messages('stranger'), limit(50))));
    await assertFails(addDoc(messages('stranger'), msg('stranger', 'Eve')));
    await assertFails(getDocs(query(collection(anon(), 'rooms', ROOM, 'messages'), limit(50))));
  });

  it('blocks spoofed sender, wrong username, client timestamps, long or empty text', async () => {
    await seedRoom();
    await assertFails(addDoc(messages('alice'), msg('owner', 'Owner')));
    await assertFails(addDoc(messages('alice'), msg('alice', 'Owner')));
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice', { timestamp: new Date(0) })));
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice', { messageText: '' })));
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice', { messageText: 'x'.repeat(2001) })));
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice', { admin: true })));
  });

  it('blocks sending to a closed room', async () => {
    await seedRoom({ roomOpen: false });
    await assertFails(addDoc(messages('alice'), msg('alice', 'Alice')));
  });

  it('does not allow editing, and only the owner may delete messages', async () => {
    await seedRoom();
    const ref = (uid) => doc(asUser(uid), 'rooms', ROOM, 'messages', 'm1');
    await assertFails(updateDoc(ref('alice'), { messageText: 'edited' }));
    await assertFails(deleteDoc(ref('alice')));
    await assertSucceeds(deleteDoc(ref('owner')));
  });
});

describe('presence', () => {
  const presence = (uid, over = {}) => ({
    username: uid === 'alice' ? 'Alice' : 'Owner', isPresent: true, lastSeen: serverTimestamp(), ...over,
  });

  it('lets a member write only their own presence', async () => {
    await seedRoom();
    await assertSucceeds(setDoc(doc(asUser('alice'), 'rooms', ROOM, 'presence', 'alice'), presence('alice')));
    await assertFails(setDoc(doc(asUser('alice'), 'rooms', ROOM, 'presence', 'owner'), presence('alice')));
    await assertFails(setDoc(doc(asUser('stranger'), 'rooms', ROOM, 'presence', 'stranger'), presence('alice')));
  });

  it('lets the owner clear presence docs when deleting the room', async () => {
    await seedRoom();
    await assertSucceeds(setDoc(doc(asUser('alice'), 'rooms', ROOM, 'presence', 'alice'), presence('alice')));
    await assertSucceeds(deleteDoc(doc(asUser('owner'), 'rooms', ROOM, 'presence', 'alice')));
  });
});
