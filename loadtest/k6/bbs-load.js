import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PASSWORD = __ENV.LOADTEST_PASSWORD || 'pass123';
const SETUP_USER_COUNT = Number(__ENV.SETUP_USER_COUNT || 12);
const SETUP_POSTS_PER_USER = Number(__ENV.SETUP_POSTS_PER_USER || 4);
const RESULT_FILE = __ENV.K6_SUMMARY_FILE || 'loadtest/results/k6-summary.json';

const browseListDuration = new Trend('browse_list_duration');
const hotPostDetailDuration = new Trend('hot_post_detail_duration');
const searchDuration = new Trend('search_duration');
const createPostDuration = new Trend('create_post_duration');
const createCommentDuration = new Trend('create_comment_duration');
const likeAddRaceDuration = new Trend('like_add_race_duration');
const likeRemoveRaceDuration = new Trend('like_remove_race_duration');
const bookmarkMixedDuration = new Trend('bookmark_mixed_duration');
const followMixedDuration = new Trend('follow_mixed_duration');
const blockMixedDuration = new Trend('block_mixed_duration');
const notificationReadWriteMixedDuration = new Trend('notification_read_write_mixed_duration');
const unexpectedResponseRate = new Rate('unexpected_response_rate');

const bookmarkMixedVus = Number(__ENV.BOOKMARK_MIXED_VUS || 0);
const followMixedVus = Number(__ENV.FOLLOW_MIXED_VUS || 0);
const blockMixedVus = Number(__ENV.BLOCK_MIXED_VUS || 0);
const notificationMixedVus = Number(__ENV.NOTIFICATION_MIXED_VUS || 0);

const scenarios = {
  browse_board_feed: {
    executor: 'constant-arrival-rate',
    exec: 'browseBoardFeed',
    rate: Number(__ENV.BROWSE_RATE || 30),
    timeUnit: '1s',
    duration: __ENV.BROWSE_DURATION || '1m',
    preAllocatedVUs: Number(__ENV.BROWSE_PRE_ALLOCATED_VUS || 20),
    maxVUs: Number(__ENV.BROWSE_MAX_VUS || 60),
  },
  hot_post_details: {
    executor: 'constant-arrival-rate',
    exec: 'hotPostDetails',
    rate: Number(__ENV.HOT_POST_RATE || 40),
    timeUnit: '1s',
    duration: __ENV.HOT_POST_DURATION || '1m',
    preAllocatedVUs: Number(__ENV.HOT_POST_PRE_ALLOCATED_VUS || 20),
    maxVUs: Number(__ENV.HOT_POST_MAX_VUS || 80),
  },
  search_catalog: {
    executor: 'constant-arrival-rate',
    exec: 'searchCatalog',
    rate: Number(__ENV.SEARCH_RATE || 15),
    timeUnit: '1s',
    duration: __ENV.SEARCH_DURATION || '1m',
    preAllocatedVUs: Number(__ENV.SEARCH_PRE_ALLOCATED_VUS || 10),
    maxVUs: Number(__ENV.SEARCH_MAX_VUS || 40),
  },
  write_posts_and_comments: {
    executor: 'ramping-arrival-rate',
    exec: 'writePostsAndComments',
    startRate: Number(__ENV.WRITE_START_RATE || 2),
    timeUnit: '1s',
    preAllocatedVUs: Number(__ENV.WRITE_PRE_ALLOCATED_VUS || 10),
    maxVUs: Number(__ENV.WRITE_MAX_VUS || 40),
    stages: [
      { target: Number(__ENV.WRITE_STAGE_ONE_RATE || 5), duration: __ENV.WRITE_STAGE_ONE_DURATION || '30s' },
      { target: Number(__ENV.WRITE_STAGE_TWO_RATE || 10), duration: __ENV.WRITE_STAGE_TWO_DURATION || '30s' },
    ],
  },
  like_idempotent_add_race: {
    executor: 'constant-vus',
    exec: 'likeAddRace',
    vus: Number(__ENV.LIKE_ADD_VUS || 30),
    duration: __ENV.LIKE_ADD_DURATION || '45s',
  },
  like_idempotent_remove_race: {
    executor: 'constant-vus',
    exec: 'likeRemoveRace',
    vus: Number(__ENV.LIKE_REMOVE_VUS || 30),
    duration: __ENV.LIKE_REMOVE_DURATION || '45s',
  },
};

if (bookmarkMixedVus > 0) {
  scenarios.bookmark_mixed_race = {
    executor: 'constant-vus',
    exec: 'bookmarkMixedRace',
    vus: bookmarkMixedVus,
    duration: __ENV.BOOKMARK_MIXED_DURATION || '45s',
  };
}

if (followMixedVus > 0) {
  scenarios.follow_mixed_race = {
    executor: 'constant-vus',
    exec: 'followMixedRace',
    vus: followMixedVus,
    duration: __ENV.FOLLOW_MIXED_DURATION || '45s',
  };
}

if (blockMixedVus > 0) {
  scenarios.block_mixed_race = {
    executor: 'constant-vus',
    exec: 'blockMixedRace',
    vus: blockMixedVus,
    duration: __ENV.BLOCK_MIXED_DURATION || '45s',
  };
}

if (notificationMixedVus > 0) {
  scenarios.notification_read_write_mixed = {
    executor: 'constant-vus',
    exec: 'notificationReadWriteMixed',
    vus: notificationMixedVus,
    duration: __ENV.NOTIFICATION_MIXED_DURATION || '45s',
  };
}

export const options = {
  scenarios,
  thresholds: {
    unexpected_response_rate: ['rate<0.01'],
    browse_list_duration: ['p(95)<400', 'p(99)<800'],
    hot_post_detail_duration: ['p(95)<450', 'p(99)<900'],
    search_duration: ['p(95)<500', 'p(99)<1000'],
    create_post_duration: ['p(95)<800', 'p(99)<1500'],
    create_comment_duration: ['p(95)<700', 'p(99)<1300'],
    like_add_race_duration: ['p(95)<400', 'p(99)<700'],
    like_remove_race_duration: ['p(95)<400', 'p(99)<700'],
    bookmark_mixed_duration: ['p(95)<400', 'p(99)<700'],
    follow_mixed_duration: ['p(95)<400', 'p(99)<700'],
    block_mixed_duration: ['p(95)<400', 'p(99)<700'],
    notification_read_write_mixed_duration: ['p(95)<500', 'p(99)<900'],
  },
};

export function setup() {
  resetLoadTestMetrics();

  // 문제 해결:
  // 반복 스트레스 실행 후 SQL로 정합성을 검증하려면 각 런이 만든 사용자/게시물을
  // 서로 구분할 수 있어야 한다. 외부에서 고정 prefix를 주입할 수 있게 해
  // "이번 실행이 만든 데이터만" 범위를 좁혀 검증한다.
  const prefix = __ENV.LOADTEST_PREFIX || `lt${Date.now().toString(36).slice(-6)}`;
  const boards = ['free', 'dev', 'qna'];
  const seedUsers = [];
  const hotPostIds = [];

  // 문제 해결:
  // 읽기/쓰기/경합 시나리오가 전부 같은 빈 DB에서 시작하면 특정 경로만 과하게 유리해진다.
  // setup 단계에서 사용자와 게시물을 미리 분산 생성해 실제 BBS 트래픽과 비슷한 데이터 분포를 만든다.
  for (let i = 0; i < SETUP_USER_COUNT; i += 1) {
    const user = signupUser(`${prefix}u${i}`);
    seedUsers.push(user);

    for (let j = 0; j < SETUP_POSTS_PER_USER; j += 1) {
      const boardSlug = boards[(i + j) % boards.length];
      const response = createPost(user.token, {
        title: `${prefix} title ${i}-${j}`,
        content: `${prefix} content ${i}-${j} for load and search`,
        tagsInput: `loadtest,${boardSlug},spring`,
        boardSlug,
        draft: false,
      }, true);

      if (hotPostIds.length < 12) {
        hotPostIds.push(response.id);
      }
    }
  }

  for (let i = 0; i < Math.min(seedUsers.length, hotPostIds.length); i += 1) {
    createComment(seedUsers[i].token, hotPostIds[i], `${prefix} seeded comment ${i}`, true);
  }

  const bookmarkMixedUser = signupUser(`${prefix}bm0`);
  const bookmarkMixedPostId = hotPostIds[2] || hotPostIds[0];
  const followMixedActor = signupUser(`${prefix}fw0`);
  const followMixedTarget = signupUser(`${prefix}fw1`);
  const blockMixedActor = signupUser(`${prefix}bk0`);
  const blockMixedTarget = signupUser(`${prefix}bk1`);
  const notificationOwner = signupUser(`${prefix}nt0`);
  const notificationTargetPost = createPost(notificationOwner.token, {
    title: `${prefix} notification target`,
    content: `${prefix} notification target content`,
    tagsInput: 'loadtest,notification',
    boardSlug: boards[0],
    draft: false,
  }, true);
  const notificationWriterUsers = [
    signupUser(`${prefix}nw0`),
    signupUser(`${prefix}nw1`),
    signupUser(`${prefix}nw2`),
  ];

  const likeAddRaceUser = signupUser(`${prefix}la0`);
  const likeRemoveRaceUser = signupUser(`${prefix}lr0`);
  const likeAddRacePostId = hotPostIds[0];
  const likeRemoveRacePostId = hotPostIds[1] || hotPostIds[0];

  http.post(`${BASE_URL}/api/posts/${likeRemoveRacePostId}/like`, null, authParams(likeRemoveRaceUser.token));

  return {
    boards,
    hotPostIds,
    seedUsers,
    bookmarkMixedUser,
    bookmarkMixedPostId,
    followMixedActor,
    followMixedTarget,
    blockMixedActor,
    blockMixedTarget,
    notificationOwner,
    notificationTargetPostId: notificationTargetPost.id,
    notificationWriterUsers,
    likeAddRaceUser,
    likeAddRacePostId,
    likeRemoveRaceUser,
    likeRemoveRacePostId,
  };
}

export function browseBoardFeed(data) {
  const boardSlug = data.boards[exec.scenario.iterationInTest % data.boards.length];
  const sorts = ['latest', 'likes', 'comments'];
  const sort = sorts[exec.scenario.iterationInTest % sorts.length];
  const response = http.get(`${BASE_URL}/api/posts?boardSlug=${boardSlug}&sort=${sort}&page=0&size=20`);

  browseListDuration.add(response.timings.duration);
  recordResponse(response, [200], `browse board ${boardSlug}`);
}

export function hotPostDetails(data) {
  const postId = data.hotPostIds[exec.scenario.iterationInTest % data.hotPostIds.length];
  const response = http.get(`${BASE_URL}/api/posts/${postId}`);

  hotPostDetailDuration.add(response.timings.duration);
  recordResponse(response, [200], `hot post detail ${postId}`);
}

export function searchCatalog(data) {
  const boardSlug = data.boards[exec.scenario.iterationInTest % data.boards.length];
  const queries = [
    `${BASE_URL}/api/posts?boardSlug=${boardSlug}&keyword=load&page=0&size=20`,
    `${BASE_URL}/api/posts?tag=spring&page=0&size=20`,
    `${BASE_URL}/api/posts?boardSlug=${boardSlug}&sort=likes&page=0&size=20`,
  ];

  for (const url of queries) {
    const response = http.get(url);
    searchDuration.add(response.timings.duration);
    recordResponse(response, [200], 'search catalog');
  }
}

export function writePostsAndComments(data) {
  const user = pickUser(data.seedUsers);
  const createdPost = createPost(user.token, {
    title: `write-${exec.scenario.iterationInTest}-${exec.vu.idInTest}`,
    content: `write content ${Date.now()}`,
    tagsInput: 'loadtest,write',
    boardSlug: data.boards[exec.scenario.iterationInTest % data.boards.length],
    draft: false,
  });

  createPostDuration.add(createdPost.duration);

  const hotPostId = data.hotPostIds[exec.scenario.iterationInTest % data.hotPostIds.length];
  const commentResponse = http.post(
    `${BASE_URL}/api/posts/${hotPostId}/comments`,
    JSON.stringify({ content: `comment ${exec.scenario.iterationInTest}-${exec.vu.idInTest}` }),
    authParams(user.token)
  );

  createCommentDuration.add(commentResponse.timings.duration);
  recordResponse(commentResponse, [201], `comment hot post ${hotPostId}`);
}

export function likeAddRace(data) {
  const response = http.post(
    `${BASE_URL}/api/posts/${data.likeAddRacePostId}/like`,
    null,
    authParams(data.likeAddRaceUser.token)
  );

  likeAddRaceDuration.add(response.timings.duration);
  recordResponse(response, [200], 'idempotent like add');
}

export function likeRemoveRace(data) {
  const response = http.del(
    `${BASE_URL}/api/posts/${data.likeRemoveRacePostId}/like`,
    null,
    authParams(data.likeRemoveRaceUser.token)
  );

  likeRemoveRaceDuration.add(response.timings.duration);
  recordResponse(response, [200], 'idempotent like remove');
}

export function bookmarkMixedRace(data) {
  // 문제 해결:
  // 등록만 반복하면 "이미 등록됨" 경로만, 해제만 반복하면 "이미 해제됨" 경로만 두드리게 된다.
  // 같은 대상에 add/remove를 교차시켜 토글이 아니라 멱등 상태 보장이 실제로 유지되는지 확인한다.
  const shouldBookmark = isActivateTurn();
  const response = loadTestAction(
    shouldBookmark ? '/internal/load-test/actions/bookmark' : '/internal/load-test/actions/unbookmark',
    {
      username: data.bookmarkMixedUser.username,
      postId: data.bookmarkMixedPostId,
    }
  );

  bookmarkMixedDuration.add(response.timings.duration);
  recordResponse(response, [200], shouldBookmark ? 'bookmark mixed add' : 'bookmark mixed remove');
}

export function followMixedRace(data) {
  const shouldFollow = isActivateTurn();
  const response = loadTestAction(
    shouldFollow ? '/internal/load-test/actions/follow' : '/internal/load-test/actions/unfollow',
    {
      actorUsername: data.followMixedActor.username,
      targetUsername: data.followMixedTarget.username,
    }
  );

  followMixedDuration.add(response.timings.duration);
  recordResponse(response, [200], shouldFollow ? 'follow mixed add' : 'follow mixed remove');
}

export function blockMixedRace(data) {
  const shouldBlock = isActivateTurn();
  const response = loadTestAction(
    shouldBlock ? '/internal/load-test/actions/block' : '/internal/load-test/actions/unblock',
    {
      actorUsername: data.blockMixedActor.username,
      targetUsername: data.blockMixedTarget.username,
    }
  );

  blockMixedDuration.add(response.timings.duration);
  recordResponse(response, [200], shouldBlock ? 'block mixed add' : 'block mixed remove');
}

export function notificationReadWriteMixed(data) {
  // 문제 해결:
  // 알림 정합성은 "읽음 처리만" 혹은 "생성만"으로는 검증되지 않는다.
  // 같은 수신자에 대해 read-all과 신규 알림 생성을 섞어 unread counter drift를 재현한다.
  const readTurn = isActivateTurn();
  let response;

  if (readTurn) {
    response = loadTestAction('/internal/load-test/actions/notifications/read-all', {
      username: data.notificationOwner.username,
    });
    recordResponse(response, [200], 'notification mixed read-all');
  } else {
    const writer = pickWriter(data.notificationWriterUsers);
    response = http.post(
      `${BASE_URL}/api/posts/${data.notificationTargetPostId}/comments`,
      JSON.stringify({ content: `notification mixed ${exec.scenario.iterationInTest}-${exec.vu.idInTest}-${Date.now()}` }),
      authParams(writer.token)
    );
    recordResponse(response, [201], 'notification mixed comment');
  }

  notificationReadWriteMixedDuration.add(response.timings.duration);
}

export function handleSummary(data) {
  const lines = [
    'BBS load test summary',
    `http_req_duration p95=${formatMetric(data, 'http_req_duration', 'p(95)')} p99=${formatMetric(data, 'http_req_duration', 'p(99)')}`,
    `browse_list_duration p95=${formatMetric(data, 'browse_list_duration', 'p(95)')} p99=${formatMetric(data, 'browse_list_duration', 'p(99)')}`,
    `hot_post_detail_duration p95=${formatMetric(data, 'hot_post_detail_duration', 'p(95)')} p99=${formatMetric(data, 'hot_post_detail_duration', 'p(99)')}`,
    `search_duration p95=${formatMetric(data, 'search_duration', 'p(95)')} p99=${formatMetric(data, 'search_duration', 'p(99)')}`,
    `create_post_duration p95=${formatMetric(data, 'create_post_duration', 'p(95)')} p99=${formatMetric(data, 'create_post_duration', 'p(99)')}`,
    `create_comment_duration p95=${formatMetric(data, 'create_comment_duration', 'p(95)')} p99=${formatMetric(data, 'create_comment_duration', 'p(99)')}`,
    `like_add_race_duration p95=${formatMetric(data, 'like_add_race_duration', 'p(95)')} p99=${formatMetric(data, 'like_add_race_duration', 'p(99)')}`,
    `like_remove_race_duration p95=${formatMetric(data, 'like_remove_race_duration', 'p(95)')} p99=${formatMetric(data, 'like_remove_race_duration', 'p(99)')}`,
    `bookmark_mixed_duration p95=${formatMetric(data, 'bookmark_mixed_duration', 'p(95)')} p99=${formatMetric(data, 'bookmark_mixed_duration', 'p(99)')}`,
    `follow_mixed_duration p95=${formatMetric(data, 'follow_mixed_duration', 'p(95)')} p99=${formatMetric(data, 'follow_mixed_duration', 'p(99)')}`,
    `block_mixed_duration p95=${formatMetric(data, 'block_mixed_duration', 'p(95)')} p99=${formatMetric(data, 'block_mixed_duration', 'p(99)')}`,
    `notification_read_write_mixed_duration p95=${formatMetric(data, 'notification_read_write_mixed_duration', 'p(95)')} p99=${formatMetric(data, 'notification_read_write_mixed_duration', 'p(99)')}`,
    `unexpected_response_rate=${formatMetric(data, 'unexpected_response_rate', 'rate')}`,
  ];

  return {
    stdout: `${lines.join('\n')}\n`,
    [RESULT_FILE]: JSON.stringify(data, null, 2),
  };
}

function signupUser(username) {
  const response = http.post(
    `${BASE_URL}/api/auth/signup`,
    JSON.stringify({
      username,
      email: `${username}@load.test`,
      password: PASSWORD,
    }),
    jsonParams()
  );

  assertExpectedStatus(response, [200], `signup ${username}`);
  const body = response.json();
  return { username, token: body.token };
}

function createPost(token, payload, strict = false) {
  const response = http.post(
    `${BASE_URL}/api/posts`,
    JSON.stringify(payload),
    authParams(token)
  );

  if (strict) {
    assertExpectedStatus(response, [201], `create post ${payload.boardSlug}`);
  } else {
    recordResponse(response, [201], `create post ${payload.boardSlug}`);
  }
  const body = response.json();
  return {
    id: body.id,
    duration: response.timings.duration,
  };
}

function createComment(token, postId, content, strict = false) {
  const response = http.post(
    `${BASE_URL}/api/posts/${postId}/comments`,
    JSON.stringify({ content }),
    authParams(token)
  );

  if (strict) {
    assertExpectedStatus(response, [201], `seed comment ${postId}`);
  } else {
    recordResponse(response, [201], `seed comment ${postId}`);
  }
  return response;
}

function resetLoadTestMetrics() {
  const response = http.post(`${BASE_URL}/internal/load-test/reset`, null);
  assertExpectedStatus(response, [200], 'reset load test metrics');
}

function loadTestAction(path, payload) {
  return http.post(
    `${BASE_URL}${path}`,
    JSON.stringify(payload),
    jsonParams()
  );
}

function pickUser(users) {
  const index = (exec.vu.idInTest + exec.scenario.iterationInTest) % users.length;
  return users[index];
}

function pickWriter(users) {
  const index = (exec.vu.idInTest + exec.scenario.iterationInTest) % users.length;
  return users[index];
}

function isActivateTurn() {
  // 문제 해결:
  // 같은 시나리오 안에서 add/remove 요청을 반반 섞어야 "활성화 직후 해제"와
  // "해제 직후 재활성화" 경합을 동시에 재현할 수 있다.
  return (exec.vu.idInTest + exec.scenario.iterationInTest) % 2 === 0;
}

function recordResponse(response, expectedStatuses, label) {
  const ok = check(response, {
    [`${label} status ok`]: (res) => expectedStatuses.includes(res.status),
  });

  if (!ok) {
    unexpectedResponseRate.add(1);
  } else {
    unexpectedResponseRate.add(0);
  }
}

function assertExpectedStatus(response, expectedStatuses, label) {
  recordResponse(response, expectedStatuses, label);
  if (!expectedStatuses.includes(response.status)) {
    throw new Error(`${label} failed with status ${response.status}`);
  }
}

function authParams(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}

function jsonParams() {
  return {
    headers: {
      'Content-Type': 'application/json',
    },
  };
}

function formatMetric(data, metricName, statName) {
  const metric = data.metrics[metricName];
  if (!metric || !metric.values || metric.values[statName] === undefined) {
    return 'n/a';
  }

  const value = metric.values[statName];
  if (statName === 'rate') {
    return value.toFixed(4);
  }
  return `${value.toFixed(2)}ms`;
}
