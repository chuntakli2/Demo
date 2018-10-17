$(document).ready(function(){
	/*
	$(window).bind(
	  'touchmove',
	   function(e) {
	    e.preventDefault();
	  }
	); */
	
	var $button_list = $("#button_list"), $current_button = $("#button_2"), i=1;
	
	$button_list.find('a').live('click', function(e){
		$current_button.removeClass('button_on');
		$(this).addClass('button_on');
		$current_button = $(this);
		
		for(i=1; i<=6; i++){
			$("#output_" + i).text($current_button.attr('data-' + i) + '万');
		}
		
		e.preventDefault();
	});
	
	$current_button.trigger('click');
	
	/*
	
	//Update Counter
	$.post("include/functions.php",{'action': "updateCounter"}, function(msg){
		if (msg.status == 1) {
			
		}else alert(msg.txt);
	}, "json");

	*/
	
});