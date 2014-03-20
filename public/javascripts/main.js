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


var app = angular.module('app', ["xeditable"]).
        config(function ($httpProvider) {
                   $httpProvider.defaults.withCredentials = true;
                });

app.run(function(editableOptions) {
  editableOptions.theme = 'bs3'; // bootstrap3 theme. Can be also 'bs2', 'default'
});

app.factory('ChatService', function() {
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
        console.log("ack websocket"); //(send ack message? later, could help avoid initializing too many websockets)
        service.send("ack")
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

  //map of user_id => object describing public components of user
  $scope.init = function (current_user, username) {
    $scope.current_user = current_user;
    $scope.users[current_user] = username;
  }

  $scope.users = {};

  $scope.messages = {};

  //init to null (binding in init)
  $scope.current_user = null;

  $scope.unfollow_user = function(user_id) {
        console.log("unfollow user " + user_id);
        $http({ method: 'GET', url: '/user/unfollow/' + user_id }).
            success(function(data, status, headers, config) {
                  console.log("unfollowed user " + user_id + ", " + JSON.stringify(data));
                  $scope.get_user(user_id).following = false;
            }).
            error(function(data, status, headers, config) {
                  console.log("failed to unfollow user " + user_id + ", " + status);
        });
  }


  $scope.follow_user = function(user_id) {
        console.log("follow user " + user_id);
        $http({ method: 'GET', url: '/user/follow/' + user_id }).
            success(function(data, status, headers, config) {
                  console.log("followed user " + user_id + ", " + JSON.stringify(data));
                   $scope.get_user(user_id).following = true;
            }).
            error(function(data, status, headers, config) {
                  console.log("failed to follow user " + user_id + ", " + status);
        });
  }


  ChatService.subscribe(function(message) {
            var actual = jQuery.parseJSON(message)

            if ('msg' in actual && 'pid' in actual){
                var msg = actual['msg'];
                var post_id = actual['pid'];

                console.log(post_id + " => " + JSON.stringify(msg));


                $scope.messages[post_id] = msg;
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
		ChatService.send( {msg:text} );
		$("#tweeter").val("");
	}
  };

}