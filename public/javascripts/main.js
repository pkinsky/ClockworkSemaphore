//dynamic navbar offset
$(window).resize(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});

$(window).load(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});

//thank you http://stackoverflow.com/users/96100/tim-down
function StringSet() {
    var setObj = {}, val = {};

    this.add = function(str) {
        setObj[str] = val;
    };

    this.contains = function(str) {
        return setObj[str] === val;
    };

    this.remove = function(str) {
        delete setObj[str];
    };

    this.values = function() {
        var values = [];
        for (var i in setObj) {
            if (setObj[i] === val) {
                values.push(i);
            }
        }
        return values;
    };
}


var app = angular.module('app', []).
        config(function ($httpProvider) {
                   $httpProvider.defaults.withCredentials = true;
                });


app.factory('ChatService', function() {
  var service = {};

  function ws_url(s) {
      var l = window.location;
      var r = ((l.protocol === "https:") ? "wss://" : "ws://") + l.hostname + (((l.port != 80) && (l.port != 443)) ? ":" + l.port : "") + l.pathname + s;
      //console.log(r);
      return r;
  }

  service.connect = function() {
    if(service.ws) { return; }

    var ws = new ReconnectingWebSocket(ws_url("websocket/"));

    ws.onopen = function() {
        service.send( {'feed': 'my_feed', 'page': 0} ) //fetch user's feed
    };

    ws.onerror = function() {
      console.error("error, failed to open connection");
    }

    ws.onmessage = function(message) {
      service.callback(message.data);
    };

    service.ws = ws;
  }

  service.send = function(message) {
    service.ws.send(JSON.stringify(message));
  }

  service.subscribe = function(callback) {
    service.callback = callback;
  }

  return service;
});


function AppCtrl($scope, $http, ChatService) {
  ChatService.connect();

   setInterval(function(){
                         console.log("send ping");
                         ChatService.send("ping");
                     }, 30000);

  //map of user_id => object describing public components of user
  $scope.init = function (current_user, username) {
    $scope.current_user = current_user;
    $scope.users[current_user] = {'uid':current_user, 'username': username, 'isFollowing':false};
  }


  $scope.fetch_feed = function(feed, page){
      if ("my_feed" == feed) {
            $scope.fetch_my_feed(page)
      }
      if ("global_feed" == feed) {
            $scope.fetch_global_feed(page)
      }
  }

  $scope.fetch_my_feed = function(page) {
      ChatService.send( {'feed': 'my_feed', 'page': page} );
  }

  $scope.fetch_global_feed = function(page) {
      ChatService.send( {'feed': 'global_feed', 'page': page} );
  }


  $scope.users = {};

  $scope.messages = {};

  $scope.get_messages = function(feed) {
    var all_messages = _.map($scope.messages, function(v, k) { return v; });


    var r = []

    if (feed == "my_feed") {
        //console.log("filter against stringset " + JSON.stringify(my_feed_messages.values()));
        r =  _.filter(all_messages, function(elem){
           // console.log("   filter against " + JSON.stringify(elem))
            return my_feed_messages.contains(elem.post_id)
        } );
    }

    if (feed == "global_feed") {
        //console.log("filter against stringset " + JSON.stringify(global_feed_messages.values()));
        r =  _.filter(all_messages, function(elem){ return global_feed_messages.contains(elem.post_id) } );
    }

    //console.log("result is " + JSON.stringify(r) + " for feed " + feed + ", filtered from " + JSON.stringify(all_messages));

    return r;
  }

  $scope.feed = "my_feed"

  $scope.feed_page = {'my_feed': 0, 'global_feed': 0};

  //init to null (binding in init)
  $scope.current_user = null;

  $scope.unfollow_user = function(user_id) {
        //console.log("unfollow user " + user_id);
        $http({ method: 'GET', url: '/user/unfollow/' + user_id }).
            success(function(data, status, headers, config) {
                  console.log("unfollowed user " + user_id + ", " + JSON.stringify(data));
                  $scope.users[user_id].isFollowing  = false;
            }).
            error(function(data, status, headers, config) {
                  console.log("failed to unfollow user " + user_id + ", " + status);
        });
  }


  $scope.follow_user = function(user_id) {
        //console.log("follow user " + user_id);
        $http({ method: 'GET', url: '/user/follow/' + user_id }).
            success(function(data, status, headers, config) {
                  console.log("followed user " + user_id + ", " + JSON.stringify(data));
                  $scope.users[user_id].isFollowing = true;
            }).
            error(function(data, status, headers, config) {
                  console.log("failed to follow user " + user_id + ", " + status);
        });
  }

  var global_feed_messages = new StringSet();
  var my_feed_messages = new StringSet();

  ChatService.subscribe(function(update) {
            console.log("msg| =>\n" + update);

            update = jQuery.parseJSON(update);

            if ('feed' in update && 'users' in update && 'messages' in update){

                update.messages.forEach( function(msg) {
                    //console.log(JSON.stringify(msg));

                    var post_id = msg.post_id;


                    if ("my_feed" == update.feed) {
                        my_feed_messages.add(post_id);
                        global_feed_messages.add(post_id);
                    }else if ("global_feed" == update.feed) {
                        global_feed_messages.add(post_id);
                    }


                    $scope.messages[post_id] = msg;

                });

                update.users.forEach( function(user){
                        $scope.users[user.uid] = user;
                    }
                )
            }

            $scope.$apply();
      }
  );

  $scope.connect = function() {
    ChatService.connect();
  };

  $scope.send = function() {
    var text = $("#tweeter").val();
	if (text.length > 0){
		ChatService.send( {'msg':text} );
		$("#tweeter").val("");
	}
  };

}