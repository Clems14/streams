/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.twitter.provider;

import org.apache.streams.core.StreamsDatum;
import org.apache.streams.jackson.StreamsJacksonMapper;
import org.apache.streams.twitter.api.FriendsIdsRequest;
import org.apache.streams.twitter.api.FriendsIdsResponse;
import org.apache.streams.twitter.api.Twitter;
import org.apache.streams.twitter.pojo.Follow;
import org.apache.streams.twitter.pojo.User;
import org.apache.streams.util.ComponentUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 *  Retrieve friend or follower connections for a single user id.
 */
public class TwitterFriendsIdsProviderTask implements Callable<Iterator<FriendsIdsResponse>>, Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TwitterFriendsIdsProviderTask.class);

  private static final ObjectMapper mapper = StreamsJacksonMapper.getInstance();

  protected Twitter client;
  protected TwitterFollowingProvider provider;
  protected FriendsIdsRequest request;
  protected List<FriendsIdsResponse> responseList;

  /**
   * TwitterFollowingProviderTask constructor.
   * @param provider TwitterFollowingProvider
   * @param twitter Twitter
   * @param request FriendsIdsRequest
   */
  public TwitterFriendsIdsProviderTask(TwitterFollowingProvider provider, Twitter twitter, FriendsIdsRequest request) {
    this.provider = provider;
    this.client = twitter;
    this.request = request;
  }

  @Override
  public void run() {

    Preconditions.checkArgument(request.getId() != null || request.getScreenName() != null);

    responseList = new ArrayList<>();

    LOGGER.info("Thread Starting: {}", request.toString());

    getFriendsIds(request);

    LOGGER.info("Thread Finished: {}", request.toString());

  }

  int last_count = 0;
  int page_count = 0;
  int item_count = 0;
  long cursor = 0;

  private void getFriendsIds(FriendsIdsRequest request) {

    do {

      FriendsIdsResponse response = client.ids(request);

      responseList.add(response);

      last_count = response.getIds().size();

      if (response.getIds().size() > 0) {

        for (Long id : response.getIds()) {

          Follow follow = new Follow()
              .withFollowee(
                  new User()
                      .withId(id))
              .withFollower(
                  new User()
                      .withId(request.getId())
                      .withScreenName(request.getScreenName()));

          if (item_count < provider.getConfig().getMaxItems()) {
            ComponentUtils.offerUntilSuccess(new StreamsDatum(follow), provider.providerQueue);
            item_count++;
          }

        }

      }
      page_count++;
      cursor = response.getNextCursor();
      request.setCursor(cursor);

    }
    while (shouldContinuePulling(cursor, last_count, page_count, item_count));

    LOGGER.info("item_count: {} last_count: {} page_count: {} ", item_count, last_count, page_count);

  }

  public boolean shouldContinuePulling(long cursor, int count, int page_count, int item_count) {
    return (
        cursor > 0
            && count > 0
            && item_count < provider.getConfig().getMaxItems()
            && page_count <= provider.getConfig().getMaxPages());
  }

  @Override
  public Iterator<FriendsIdsResponse> call() throws Exception {
    run();
    return responseList.iterator();
  }
}
