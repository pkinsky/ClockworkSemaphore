//dynamic navbar offset

$(window).resize(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});

$(window).load(function () {
   $('body').css('padding-top', parseInt($('#main-navbar').css("height"))+10);
});


var app = angular.module('app', []);

app.factory('ChatService', function() {

  console.log("spinning up ChatService")

  var service = {};


  function ws_url(s) {
      var l = window.location;
      var r = ((l.protocol === "https:") ? "wss://" : "ws://") + l.hostname + (((l.port != 80) && (l.port != 443)) ? ":" + l.port : "") + l.pathname + s;
      console.log(r);
      return r;
  }

  service.connect = function() {
    if(service.ws) { return; }

    var ws = new ReconnectingWebSocket(ws_url("websocket/"));

    ws.onopen = function() {
        console.log("ack websocket");
        service.ws.send(JSON.stringify("recent_posts"));
    };

    ws.onerror = function() {
      alert("error, failed to open connection");
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


function AppCtrl($scope, ChatService) {
  ChatService.connect();

  //map of user_id => object describing public components of user
  $scope.init = function (current_user, user_info) {
    $scope.current_user = current_user;
    $scope.users = user_info;
  }

  //init to null (binding in init)
  $scope.users = null;
  $scope.current_user = null;

  $scope.signup_complete = function () {
    return $scope.users[$scope.current_user].alias.length != 0;
  };

  //array of post-id's
  $scope.followed_messages = [];
  $scope.recent_messages = [];

  //private map post_id => msginfo
  var messages = {};

  //load post or get from server. need to keep list of those post_ids being fetched to avoid double-dipping
  $scope.load_message = function(post_id) {

  }

  $scope.show_followed_posts = function() {
    $scope.show_recent = false;
  }

  $scope.show_recent_posts = function() {
    $scope.show_recent = true;
  }

  //if true show recent if false show following. just fetch both at start...
  $scope.show_recent = true;

  $scope.get_messages = function() {
      if ($scope.show_recent){
        return $scope.recent_messages;
      }else{
        return $scope.followed_messages;
      }
  }


  $scope.set_alias = function() {
    var alias_in = $("#alias").val();
    console.log("setting alias: " + alias_in);
    ChatService.send( {user_id:$scope.user_id, alias:alias_in} );
  };


  $scope.push_message = function(post_id, favorite, msg) {
        msg['post_id'] = post_id;
        msg['favorite'] = favorite;
        messages[post_id] = msg;

        if (!$scope.users.hasOwnProperty(msg.user_id)){
            console.log("request info for " + msg.user_id);
            ChatService.send( {user_id: msg.user_id} );
        }
  }


  $scope.delete_message = function(message) {
    console.log("delete message");
    ChatService.send( {delete_message:message.post_id} );
    //remove msg from map? yeah why not bad removal only reflected client side anyway.
    //todo: wait on confirmation

  }


  $scope.following = function(user_id) {
    if (user_id in $scope.users){
        return $scope.users[user_id].following;
    }else{
        return false;
    }
  }


  $scope.follow_user = function(user_id) {
    console.log("follow" + user_id);

    if ($scope.users[user_id].following) {
        $scope.users[user_id].following = false;
        ChatService.send( {unfollow:user_id} );
    }else{
        $scope.users[user_id].following = true;
        ChatService.send( {follow:user_id} );
    }

  }



  $scope.favorite_message = function(message) {
    console.log("favorite message: " + JSON.stringify(message))

    var post_id = message.post_id;

    if (message.favorite) {
        message.favorite = false;
        if (!$scope.$$phase) $scope.$apply();
        ChatService.send( {unfavorite_message:message.post_id} );
    }else{
        message.favorite = true;
        if (!$scope.$$phase) $scope.$apply();
        ChatService.send( {favorite_message:message.post_id} );
    }

  }


  /*
  TODO: recent/followed _messages should be simple lists of post ids. easy to keep sorted.
        store actual messages in map {post_id => msg info}


  */


  ChatService.subscribe(function(message) {
            console.log("msg: " + message);
            var actual = jQuery.parseJSON(message)

            if ('msg' in actual){
                var messages = actual['msg'];

                messages.forEach(function(msg_info) {
                    var post_id = msg_info.post_id;
                    var favorite = msg_info.favorite;
                    var msg = msg_info.msg;
                    console.log("pushing message for info " + msg_info)
                    $scope.push_message(post_id, favorite, msg);
                });
            }

            if ('recent_msg' in actual){
                var recent = actual['recent_msg'];
                recent.forEach(function(post_id){
                    $scope.recent_messages.push(post_id);
                })
            }

            if ('followed_msg' in actual){
                var recent = actual['recent_msg'];
                recent.forEach(function(post_id){
                    $scope.followed_messages.push(post_id);
                })
            }

            if ('user_info' in actual){
                //console.log("update!");
                var user_info = actual['user_info'];
                $scope.users[user_info.user_id] = {'alias': user_info.alias, 'avatar_url': user_info.avatar_url};
            }

            if ('alias_result' in actual){
                if (actual['alias_result']['pass']){
                    $scope.users[$scope.current_user].alias = actual['alias_result']['alias'];
                } else {
                    alert("alias taken: " +  actual['alias_result']['alias']);
                }
            }

            //some voodoo for a concise delete.
            $scope.recent_messages = _.filter($scope.recent_messages, function(msg){ return !_.contains(actual.deleted, msg.post_id);});

            $scope.$apply();
      }
  );

  $scope.connect = function() {
    ChatService.connect();
  };

  $scope.send = function() {
    var text = $("#tweeter").val();
	if (text.length > 0){
		ChatService.send( {msg:text} );
		$("#tweeter").val("");
	}
  };

}