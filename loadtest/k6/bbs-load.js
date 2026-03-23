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
const unexpectedResponseRate = new Rate('unexpected_response_rate');

export const options = {
  scenarios: {
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
  },
  thresholds: {
    unexpected_response_rate: ['rate<0.01'],
    browse_list_duration: ['p(95)<400', 'p(99)<800'],
    hot_post_detail_duration: ['p(95)<450', 'p(99)<900'],
    search_duration: ['p(95)<500', 'p(99)<1000'],
    create_post_duration: ['p(95)<800', 'p(99)<1500'],
    create_comment_duration: ['p(95)<700', 'p(99)<1300'],
    like_add_race_duration: ['p(95)<400', 'p(99)<700'],
    like_remove_race_duration: ['p(95)<400', 'p(99)<700'],
  },
};

export function setup() {
  resetLoadTestMetrics();

  const prefix = `lt${Date.now().toString(36).slice(-6)}`;
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

  const likeAddRaceUser = signupUser(`${prefix}la0`);
  const likeRemoveRaceUser = signupUser(`${prefix}lr0`);
  const likeAddRacePostId = hotPostIds[0];
  const likeRemoveRacePostId = hotPostIds[1] || hotPostIds[0];

  http.post(`${BASE_URL}/api/posts/${likeRemoveRacePostId}/like`, null, authParams(likeRemoveRaceUser.token));

  return {
    boards,
    hotPostIds,
    seedUsers,
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

function pickUser(users) {
  const index = (exec.vu.idInTest + exec.scenario.iterationInTest) % users.length;
  return users[index];
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
