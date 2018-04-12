$(function() {
    // Waves初始化
    Waves.displayEffect();
    // 输入框获取焦点后出现下划线
    $('.form-control').focus(function() {
        $(this).parent().addClass('fg-toggled');
    }).blur(function() {
        $(this).parent().removeClass('fg-toggled');
    });
});
Checkbix.init();
$(function() {
    var countdown;
    var count = 90;
    var pass="";
    var registered="2";
    //判断IE浏览器版本
    if( $.browser.msie && ( $.browser.version == '6.0' || $.browser.version == '7.0'|| $.browser.version == '8.0') ){
        alert("您的浏览器版本过低，请尽快升级，否则会影响网页性能和操作！");
        return;
    };
    $("#loginData").validate({
        rules: {
            username: {
                required: true,
                minlength: 2
            },
            password: {
                required: true,
                minlength: 6,
                maxlength: 20
            },
        },
        messages: {
            username: {
                required: "请输入用户名",
                minlength: "用户名必需大于两个字母"
            },
            password: {
                required: "请输入密码",
                minlength: "密码长度不能小于 6 位",
                maxlength: "密码长度最多 20 位"
            },
        },
        errorPlacement: function (error, element) {
            if (error && error[0].innerHTML !=="") {
                // error.appendTo(element.siblings());
                cart();
                element.siblings().text(error[0].innerHTML).show();
                return;
            }else {
                element.siblings().text("").hide();
            }
        },
        success: function (element) {
        },
        submitHandler: function(form) {
            login();//表单都验证成功后的事件
        }
    });
    // 点击登录按钮
    $('#login-bt').click(function() {
        $("#loginData").validate;
    });
    // 点击重置密码按钮
    $('#resetPwd').click(function(){
        return toValidForm();
    });
    // 回车事件
    $('#username, #password').keypress(function (event) {
        if (13 == event.keyCode) {
            var flag = $("#loginData").valid();
            if(!flag){
                return;
            }
            login();
        }
    });
    function cart(){
        $('.baixiong').animate({marginLeft:'-75px;'},50);
        setTimeout(function(){
            $(".baixiong") .animate({marginLeft:'-57px'},50)
        },50);
        setTimeout(function(){
            $(".baixiong") .animate({marginLeft:'-72px'},50)
        },100);
        setTimeout(function(){
            $(".baixiong") .animate({marginLeft:'-60px'},50)
        },150);
        setTimeout(function(){
            $(".baixiong") .animate({marginLeft:'-69px'},50)
        },200);
        setTimeout(function(){
            $(".baixiong") .animate({marginLeft:'-63px'},50)
        },250);
        setTimeout(function(){
            $(".baixiong") .animate({marginLeft:'-65px'},50)
        },300);
    }
    //显示验证码
    function code(){
        $('.content-box').css('height','470px');
        $('.coderow,.changecode').show()
    }
    //切换验证码
    $('.changeimg').on('click',function(){
        var rdn = Math.random();
        var tempCaptchaUrl = $("[name=captchaImg]").attr('src');
        var index = tempCaptchaUrl.lastIndexOf('?');
        if (index != -1) {
            tempCaptchaUrl = tempCaptchaUrl.substr(0, index);
        }
        $("[name=captchaImg]").attr("src", tempCaptchaUrl + "?t=" + rdn);
    });
    //bilibili切换验证码
    $('.popimg').on('click',function(){
        var rdn = Math.random();
        var tempCaptchaUrl = $("[name=captchaImg]").attr('src');
        var index = tempCaptchaUrl.lastIndexOf('?');
        if (index != -1) {
            tempCaptchaUrl = tempCaptchaUrl.substr(0, index);
        }
        $("[name=captchaImg]").attr("src", tempCaptchaUrl + "?t=" + rdn);
    });
    //倒计时
    var phoneNum;
    $('.closeicon,.cancel').on('click',function(){
        $(this).parent().parent().parent('.pop').hide();
    });

    var handler1 = function (captchaObj) {
        $(".code-sure").click(function (e) {
            var result = captchaObj.getValidate();
            if (!result) {
                $("#notice1").show();
                setTimeout(function () {
                    $("#notice1").hide();
                }, 2000);
                e.preventDefault();
                return false;
            }else{
                $('.code-span').addClass('effective');
            }
//            var code = $('.popcode').val();
            var name = $('.username').val();
            var type='';
            if($(this).hasClass('yzm-btn')){
                type='2';
            }else{
                type='1';
            }
            if(!name){
                $('.name-null').show();
                return false;
            }
            if(!name.match(/^(((13[0-9]{1})|(15[0-3]{1})|(15[5-9]{1})|177|170|176|178|145|147|(18[0-9]{1}))+\d{8})$/)) {
                $('.format').show();
                return false;
            }
//            if(!code){
//                $('.code-pop-null').show();
//                return false;
//            }
            //手机号与图形验证码不为空，校验图形验证码是否正确
            //var text=$('.code-sure').text();
            var text=$('.effective').text();
            if(text=="重新获取"||text=="获取验证码"){
                $.ajax({
                    type: 'POST',
                    url: '/sso/code',
                    data: {name:name,type:type},
                    datatype: "text",
                    success: function(data){
                        if(data==1){
//                            $('.zhushi').hide();
                            clearInterval(countdown);
                            count=90;
                            countdown = window.setInterval(CountDown, 1000);
                            //刷新图片验证码
//                            $('.changeimg').click();
                        }
//                        else{
//                            $('.zhushi').show();
//                        }
                    }
                });
            }
        });
        // 将验证码加到id为captcha的元素里，同时会有三个input的值用于表单提交
        captchaObj.appendTo("#captcha1");
        captchaObj.onReady(function () {
            $("#wait1").hide();
        });
    };
    $.ajax({
        url: "/sso/captcha?t=" + (new Date()).getTime(), // 加随机数防止缓存
        type: "get",
        dataType: "json",
        success: function (data) {
            // 调用 initGeetest 初始化参数
            // 参数1：配置参数
            // 参数2：回调，回调的第一个参数验证码对象，之后可以使用它调用相应的接口
            initGeetest({
                gt: data.gt,
                challenge: data.challenge,
                new_captcha: data.new_captcha, // 用于宕机时表示是新验证码的宕机
                offline: !data.success, // 表示用户后台检测极验服务器是否宕机，一般不需要关注
                product: "float", // 产品形式，包括：float，popup
                width: "100%"
                // 更多配置参数请参见：http://www.geetest.com/install/sections/idx-client-sdk.html#config
            }, handler1);
        }
    });
    //关闭弹窗
    // $(document).on('click','.effective',function(){
    //     var text=$('.effective').text();
    //     if(text=="重新获取"||text=="获取验证码"){
    //         $('.popimg').attr('src','/captcha.jpg');
    //         $('.popcode').val('');
    //         $('.pop').show();
    //     }
    // });

    //    $(document).on('click','.code-sure',function () {
//        var code = $('.popcode').val();
//        var name = $('.username').val();
//        var type='';
//        if($(this).hasClass('yzm-btn')){
//            type='2';
//        }else{
//            type='1';
//        }
//        if(!name){
//            $('.name-null').show();
//            return false;
//        }
//        if(!name.match(/^(((13[0-9]{1})|(15[0-3]{1})|(15[5-9]{1})|177|170|176|178|145|147|(18[0-9]{1}))+\d{8})$/)) {
//            $('.format').show();
//            return false;
//        }
//        if(!code){
//            $('.code-pop-null').show();
//            return false;
//        }
//        //手机号与图形验证码不为空，校验图形验证码是否正确
//        //var text=$('.code-sure').text();
//        var text=$('.effective').text();
//        if(text=="重新获取"||text=="获取验证码"){
//            $.ajax({
//                type: 'POST',
//                url: '/sso/code',
//                data: {code:code,name:name,type:type},
//                datatype: "text",
//                success: function(data){
//                    if(data==1){
//                        $('.zhushi').hide();
//                        clearInterval(countdown);
//                        count=90;
//                        countdown = window.setInterval(CountDown, 1000);
//                        //刷新图片验证码
//                        $('.changeimg').click();
//                    }
//                    else{
//                        $('.zhushi').show();
//                    }
//                }
//            });
//        }
//    })
    function CountDown() {
        //$('.effective').removeClass('effective');
        $('.code-span').text(count + " 秒");
        if (count == 0) {
            //$('.code-span').addClass('effective');
            //$('.effective').text("重新获取");
            $('.code-span').text("重新获取");
            window.clearInterval(countdown);
        }
        count--;
    }
    //账号格式
    $(document).on('blur',"#userName",function(){
        $('.registered,.remind').hide();
        if(count==0||count==90){
            geshi();
        }
    });
    $(document).on('focus',"#userName",function(){
        $('.remind').hide();
        if(count==0||count==90){
            geshi();
        }
    });
    // $(document).on('focus',".popcode",function(){
    //     $('.remind').hide();
    // });
    function geshi() {
        $(".remind").hide();
        var name = $('.username').val();
        $('.registered').hide();
        if(name==''){
            $('.name-null').show();
            $('.effective').removeClass('effective');
            return false;
        }
        if (name.match(/^(((13[0-9]{1})|(15[0-3]{1})|(15[5-9]{1})|177|170|176|178|145|147|(18[0-9]{1}))+\d{8})$/)) {
            $.ajax({
                type: 'POST',
                url: '/sso/check',
                data: {name:name},
                datatype: "json",
                success: function(data){
                    if (data == 1) {
                        $('.account-null').show();
                        registered='2';
                        return false;
                    }
                    if(data == 2){
                        $('.code-span').addClass('effective');
                        return true;
                    }
                    // if(!$('.username').hasClass('findname')){
                    //     $('.registered').show();
                    //     $('.effective').removeClass('effective');
                    //     registered='1';
                    // }else{
                    //     $('.code-span').addClass('effective');
                    // }
                    // }else{
                    //     $('.code-span').addClass('effective');
                    //     $(".remind").hide();
                    //     registered='2';
                    //     return false;
                    // }
                }
            });
        }
        if (!name.match(/^(((13[0-9]{1})|(15[0-3]{1})|(15[5-9]{1})|177|170|176|178|145|147|(18[0-9]{1}))+\d{8})$/)) {
            $('.format').show();
            $('.effective').removeClass('effective')
            return false;
        }
    }
    //注册成功
    if($('.success-layel').length>0){
        success();
    }
    function success(){
        count = 2;
        $('.leavetime').text('3');
        var countdown = setInterval(CountDown, 1000);
        function CountDown() {
            $('.leavetime').text(count);
            if (count == 0) {
                window.location.href='http://sso.ibaixiong.com/sso/login';
                return false;
            }
            count--;
        }
    }
    //让低版本的ie支持input的placeholder属性
    var JPlaceHolder = {
        //检测
        _check : function(){
            return 'placeholder' in document.createElement('input');
        },
        //初始化
        init : function(){
            if(!this._check()){
                this.fix();
            }
        },
        //修复
        fix : function(){
            jQuery(':input[placeholder]').each(function(index, element) {
                var self = $(this), txt = self.attr('placeholder');
                self.wrap($('<div></div>').css({position:'relative', zoom:'1', border:'none', background:'none', padding:'none', margin:'none'}));
                var pos = self.position(), h = self.outerHeight(true), paddingleft = self.css('padding-left');
                var holder = $('<span></span>').text(txt).css({position:'absolute', left:10+'px', top:pos.top, height:h, lineHeight:h+'px', paddingLeft:paddingleft, color:'#aaa'}).appendTo(self.parent());
                self.focusin(function(e) {
                    holder.hide();
                }).focusout(function(e) {
                    if(!self.val()){
                        holder.show();
                    }
                });
                holder.click(function(e) {
                    holder.hide();
                    self.focus();
                });
                if(self.val()){
                    holder.hide();
                }
            });
        }
    };
    //执行
    jQuery(function(){
        JPlaceHolder.init();
    });
});
// 登录
function login() {
    $.ajax({
        url: BASE_PATH + '/sso/login',
        type: 'POST',
        data: {
            username: $('#username').val(),
            password: $('#password').val(),
            appid : "papabear-merchant-admin",
            rememberMe: $('#rememberMe').is(':checked'),
            backurl: BACK_URL
        },
        beforeSend: function() {

        },
        success: function(json){
            if (json.code == 200) {
                location.href = json.data;
            } else {
                if (40103 == json.code || 40105 == json.code) {
                    $('.name-null').text(json.message).show();
                    return;
                }
                if (40104 == json.code || 40106 == json.code) {
                    $('.pass-null').text(json.message).show();
                    return;
                }
                // if (10101 == json.code) {
                //     $('#username').focus();
                // }
                // if (10102 == json.code) {
                //     $('#password').focus();
                // }
            }
        },
        error: function(error){
            console.log(error);
        }
    });
}

//验证是否为空
var toValidForm = function(registered,pass){
    $('.remind').hide();
    var name=$('.username').val();
    var password=$('.userpassword').val();
    if(name==''){
        $('.name-null').show();
        pass="2";
        return false;
    }else{
        $('.name-null').hide();
    }
    if(registered=='1'){
        $('.registered').show();
        pass="2";
        return false;
    }
    if (!name.match(/^(((13[0-9]{1})|(15[0-3]{1})|(15[5-9]{1})|177|170|176|178|145|147|(18[0-9]{1}))+\d{8})$/)) {
        $('.format').show();
        pass="2";
        return false;
    }else{
        $('.format').hide();
    }
    if($('.code').val()==''){
        $('.code-null').show();
        pass="2";
        return false;
    }
    if(password==''){
        $('.pass-null').show();
        pass="2";
        return false;
    }
    if(password.length<6||password.length>16){
        $('.pass-length').show();
        return false;
    }else{
        $('.pass-null').hide();
        pass="1";
        $('#registerfm').submit();
    }
}
